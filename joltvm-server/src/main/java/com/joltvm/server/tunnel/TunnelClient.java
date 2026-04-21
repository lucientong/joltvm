/*
 * Copyright 2026 lucientong.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.joltvm.server.tunnel;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Agent-side tunnel client that connects to a remote tunnel server via WebSocket.
 *
 * <p>Features:
 * <ul>
 *   <li>Outbound WebSocket connection (TLS supported)</li>
 *   <li>Auto-reconnect with exponential backoff (1s → 30s)</li>
 *   <li>Heartbeat keep-alive (30s interval)</li>
 *   <li>Proxied HTTP request handling — forwards requests to the local JoltVM server</li>
 * </ul>
 *
 * <p>Agent args:
 * <ul>
 *   <li>{@code tunnelServer} — tunnel server URL (e.g., wss://tunnel.example.com:8800/ws/agent)</li>
 *   <li>{@code tunnelToken} — pre-shared registration token</li>
 *   <li>{@code tunnelAgentId} — custom agent ID (default: hostname-pid)</li>
 * </ul>
 */
public class TunnelClient {

    private static final Logger LOG = Logger.getLogger(TunnelClient.class.getName());
    private static final Gson GSON = new Gson();

    /** Protocol constants — mirrored from TunnelProtocol (no compile-time dependency on joltvm-tunnel). */
    private static final String TYPE_REGISTER = "register";
    private static final String TYPE_REGISTERED = "registered";
    private static final String TYPE_HEARTBEAT = "heartbeat";
    private static final String TYPE_HEARTBEAT_ACK = "heartbeat_ack";
    private static final String TYPE_REQUEST = "request";
    private static final String TYPE_RESPONSE = "response";
    private static final String TYPE_ERROR = "error";

    private static final int HEARTBEAT_INTERVAL_SECONDS = 30;
    private static final int MAX_RECONNECT_DELAY_SECONDS = 30;
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;

    private final String tunnelServerUrl;
    private final String agentId;
    private final String token;
    private final int localPort;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

    private EventLoopGroup group;
    private Channel channel;
    private ScheduledExecutorService scheduler;

    /**
     * Creates a tunnel client.
     *
     * @param tunnelServerUrl the tunnel server WebSocket URL
     * @param agentId         the agent identifier
     * @param token           the registration token
     * @param localPort       the local JoltVM server port (for proxying)
     */
    public TunnelClient(String tunnelServerUrl, String agentId, String token, int localPort) {
        this.tunnelServerUrl = tunnelServerUrl;
        this.agentId = agentId;
        this.token = token;
        this.localPort = localPort;
    }

    /**
     * Creates a tunnel client from agent arguments.
     *
     * @param agentArgs parsed agent args
     * @param localPort the local JoltVM server port
     * @return the TunnelClient, or null if tunnelServer is not configured
     */
    public static TunnelClient fromAgentArgs(Map<String, String> agentArgs, int localPort) {
        String serverUrl = agentArgs.get("tunnelServer");
        if (serverUrl == null || serverUrl.isBlank()) {
            return null;
        }
        String token = agentArgs.getOrDefault("tunnelToken", "");
        String agentId = agentArgs.getOrDefault("tunnelAgentId", generateDefaultAgentId());
        return new TunnelClient(serverUrl, agentId, token, localPort);
    }

    /**
     * Starts the tunnel client on a daemon thread.
     */
    public void start() {
        if (!running.compareAndSet(false, true)) {
            LOG.warning("Tunnel client is already running");
            return;
        }

        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "joltvm-tunnel");
            t.setDaemon(true);
            return t;
        });

        // Start heartbeat
        scheduler.scheduleAtFixedRate(this::sendHeartbeat,
                HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);

        // Connect
        connect();
    }

    /**
     * Stops the tunnel client.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        LOG.info("Stopping tunnel client...");
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        if (channel != null && channel.isActive()) {
            channel.close();
        }
        if (group != null) {
            group.shutdownGracefully();
        }
        connected.set(false);
        LOG.info("Tunnel client stopped");
    }

    public boolean isConnected() { return connected.get(); }
    public String getAgentId() { return agentId; }

    private void connect() {
        if (!running.get()) return;

        try {
            URI uri = new URI(tunnelServerUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (port == -1) {
                port = "wss".equals(scheme) ? 443 : 80;
            }

            boolean ssl = "wss".equals(scheme);
            SslContext sslContext = ssl
                    ? SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build()
                    : null;

            group = new NioEventLoopGroup(1, r -> {
                Thread t = new Thread(r, "joltvm-tunnel-io");
                t.setDaemon(true);
                return t;
            });

            WebSocketClientHandshaker handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                    uri, WebSocketVersion.V13, null, true, new DefaultHttpHeaders());

            final int finalPort = port;
            Bootstrap bootstrap = new Bootstrap();
            bootstrap.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (sslContext != null) {
                                ch.pipeline().addFirst(sslContext.newHandler(ch.alloc(), host, finalPort));
                            }
                            ch.pipeline().addLast(
                                    new HttpClientCodec(),
                                    new HttpObjectAggregator(MAX_CONTENT_LENGTH),
                                    new TunnelClientHandler(handshaker)
                            );
                        }
                    });

            LOG.info("Connecting to tunnel server: " + tunnelServerUrl);
            ChannelFuture future = bootstrap.connect(host, port);
            future.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    channel = f.channel();
                    LOG.info("Connected to tunnel server: " + tunnelServerUrl);
                } else {
                    LOG.log(Level.WARNING, "Failed to connect to tunnel server", f.cause());
                    scheduleReconnect();
                }
            });

        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error connecting to tunnel server", e);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get()) return;
        int attempts = reconnectAttempts.incrementAndGet();
        int delay = Math.min((int) Math.pow(2, attempts - 1), MAX_RECONNECT_DELAY_SECONDS);
        LOG.info("Reconnecting in " + delay + "s (attempt " + attempts + ")...");
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.schedule(this::connect, delay, TimeUnit.SECONDS);
        }
    }

    private void sendHeartbeat() {
        if (connected.get() && channel != null && channel.isActive()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", TYPE_HEARTBEAT);
            msg.addProperty("timestamp", System.currentTimeMillis());
            channel.writeAndFlush(new TextWebSocketFrame(GSON.toJson(msg)));
        }
    }

    private void sendRegistration() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_REGISTER);
        msg.addProperty("agentId", agentId);
        msg.addProperty("token", token);

        Map<String, String> metadata = new HashMap<>();
        metadata.put("hostname", getHostname());
        metadata.put("pid", String.valueOf(ProcessHandle.current().pid()));
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        metadata.put("localPort", String.valueOf(localPort));
        msg.add("metadata", GSON.toJsonTree(metadata));

        channel.writeAndFlush(new TextWebSocketFrame(GSON.toJson(msg)));
        LOG.info("Sent registration as: " + agentId);
    }

    private void handleMessage(String text) {
        try {
            JsonObject msg = GSON.fromJson(text, JsonObject.class);
            if (msg == null || !msg.has("type")) return;

            String type = msg.get("type").getAsString();
            switch (type) {
                case TYPE_REGISTERED -> {
                    connected.set(true);
                    reconnectAttempts.set(0);
                    LOG.info("Successfully registered with tunnel server as: " + agentId);
                }
                case TYPE_HEARTBEAT_ACK -> LOG.finest("Heartbeat ACK received");
                case TYPE_REQUEST -> handleProxiedRequest(msg);
                case TYPE_ERROR -> LOG.warning("Tunnel server error: " +
                        (msg.has("message") ? msg.get("message").getAsString() : "unknown"));
                default -> LOG.fine("Unknown tunnel message type: " + type);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error processing tunnel message", e);
        }
    }

    /**
     * Handles a proxied HTTP request from the tunnel server by forwarding it
     * to the local JoltVM server via a simple HTTP call.
     */
    private void handleProxiedRequest(JsonObject msg) {
        String requestId = msg.has("requestId") ? msg.get("requestId").getAsString() : null;
        String method = msg.has("method") ? msg.get("method").getAsString() : "GET";
        String path = msg.has("path") ? msg.get("path").getAsString() : "/";

        if (requestId == null) {
            LOG.warning("Received proxied request without requestId");
            return;
        }

        // Execute in a thread pool to avoid blocking the I/O thread
        CompletableFuture.runAsync(() -> {
            try {
                // Build URL to local server
                String url = "http://localhost:" + localPort + path;

                // Use java.net.HttpURLConnection for simplicity
                var connection = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                connection.setRequestMethod(method);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(25_000);

                // Copy request headers
                if (msg.has("headers")) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> headers = GSON.fromJson(msg.get("headers"), Map.class);
                    if (headers != null) {
                        for (Map.Entry<String, String> h : headers.entrySet()) {
                            if (!"Host".equalsIgnoreCase(h.getKey())
                                    && !"Content-Length".equalsIgnoreCase(h.getKey())) {
                                connection.setRequestProperty(h.getKey(), h.getValue());
                            }
                        }
                    }
                }

                // Forward body for POST/PUT
                if (msg.has("body") && ("POST".equals(method) || "PUT".equals(method))) {
                    connection.setDoOutput(true);
                    byte[] body = msg.get("body").getAsString().getBytes(StandardCharsets.UTF_8);
                    connection.getOutputStream().write(body);
                    connection.getOutputStream().flush();
                }

                // Read response
                int status = connection.getResponseCode();
                Map<String, String> responseHeaders = new HashMap<>();
                for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                    if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                        responseHeaders.put(entry.getKey(), entry.getValue().get(0));
                    }
                }

                String responseBody;
                try (var is = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                    responseBody = is != null ? new String(is.readAllBytes(), StandardCharsets.UTF_8) : "";
                }

                // Send response back through tunnel
                sendResponse(requestId, status, responseHeaders, responseBody);

            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error proxying request: " + path, e);
                sendResponse(requestId, 502,
                        Map.of("Content-Type", "application/json"),
                        GSON.toJson(Map.of("error", "Agent proxy error: " + e.getMessage())));
            }
        });
    }

    private void sendResponse(String requestId, int status,
                               Map<String, String> headers, String body) {
        if (channel == null || !channel.isActive()) return;

        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_RESPONSE);
        msg.addProperty("requestId", requestId);
        msg.addProperty("status", status);
        msg.add("headers", GSON.toJsonTree(headers));
        if (body != null) {
            msg.addProperty("body", body);
        }
        channel.writeAndFlush(new TextWebSocketFrame(GSON.toJson(msg)));
    }

    private static String generateDefaultAgentId() {
        String hostname = getHostname();
        long pid = ProcessHandle.current().pid();
        return hostname + "-" + pid;
    }

    private static String getHostname() {
        try {
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Internal WebSocket client handler.
     */
    private class TunnelClientHandler extends SimpleChannelInboundHandler<Object> {

        private final WebSocketClientHandshaker handshaker;
        private ChannelPromise handshakeFuture;

        TunnelClientHandler(WebSocketClientHandshaker handshaker) {
            this.handshaker = handshaker;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            handshakeFuture = ctx.newPromise();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            connected.set(false);
            LOG.info("Disconnected from tunnel server");
            if (group != null) {
                group.shutdownGracefully();
                group = null;
            }
            scheduleReconnect();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (!handshaker.isHandshakeComplete()) {
                try {
                    handshaker.finishHandshake(ctx.channel(),
                            (io.netty.handler.codec.http.FullHttpResponse) msg);
                    handshakeFuture.setSuccess();
                    LOG.info("WebSocket handshake complete");
                    sendRegistration();
                } catch (WebSocketHandshakeException e) {
                    handshakeFuture.setFailure(e);
                    LOG.log(Level.SEVERE, "WebSocket handshake failed", e);
                }
                return;
            }

            if (msg instanceof TextWebSocketFrame textFrame) {
                handleMessage(textFrame.text());
            } else if (msg instanceof CloseWebSocketFrame) {
                ctx.close();
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!handshakeFuture.isDone()) {
                handshakeFuture.setFailure(cause);
            }
            LOG.log(Level.WARNING, "Tunnel client error", cause);
            ctx.close();
        }
    }
}
