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

package com.joltvm.tunnel;

import com.joltvm.tunnel.handler.AgentWebSocketHandler;
import com.joltvm.tunnel.handler.TunnelHttpHandler;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.io.File;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Standalone tunnel server that accepts agent WebSocket connections
 * and proxies user HTTP requests to remote agents.
 *
 * <p>Architecture:
 * <pre>
 *   ┌──────────────────────────────────────────┐
 *   │          Tunnel Server (:8800)            │
 *   │                                           │
 *   │  /ws/agent ──► AgentWebSocketHandler      │
 *   │             ──► AgentRegistry             │
 *   │             ──► RequestCorrelator          │
 *   │                                           │
 *   │  /api/tunnel/* ──► TunnelHttpHandler       │
 *   │  /api/tunnel/agents/{id}/proxy/** ──►      │
 *   │     forward via WS to agent               │
 *   │                                           │
 *   │  /* ──► Dashboard static files             │
 *   └──────────────────────────────────────────┘
 * </pre>
 */
public class TunnelServer {

    private static final Logger LOG = Logger.getLogger(TunnelServer.class.getName());

    public static final int DEFAULT_PORT = 8800;
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024; // 10 MB

    private final int port;
    private final AgentRegistry registry;
    private final RequestCorrelator correlator;
    private final SslContext sslContext;
    private final String version;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public TunnelServer(int port) {
        this(port, null, null);
    }

    public TunnelServer(int port, String tlsCertPath, String tlsKeyPath) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        this.port = port;
        this.registry = new AgentRegistry();
        this.correlator = new RequestCorrelator();
        this.sslContext = buildSslContext(tlsCertPath, tlsKeyPath);
        this.version = loadVersion();
    }

    /**
     * Adds a pre-shared registration token.
     *
     * @param token the token string
     */
    public void addRegistrationToken(String token) {
        registry.addToken(token);
    }

    /**
     * Starts the tunnel server.
     *
     * @throws Exception if the server cannot bind
     */
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            LOG.warning("Tunnel server is already running on port " + port);
            return;
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 256)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (sslContext != null) {
                                ch.pipeline().addFirst(sslContext.newHandler(ch.alloc()));
                            }
                            ch.pipeline().addLast(
                                    new HttpServerCodec(),
                                    new HttpObjectAggregator(MAX_CONTENT_LENGTH),
                                    new ChunkedWriteHandler(),
                                    new WebSocketServerProtocolHandler("/ws/agent", null, true),
                                    new AgentWebSocketHandler(registry, correlator),
                                    new TunnelHttpHandler(registry, correlator, version)
                            );
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            String scheme = sslContext != null ? "https" : "http";
            LOG.info(String.format("JoltVM Tunnel Server started on port %d — %s://localhost:%d",
                    port, scheme, port));
        } catch (Exception e) {
            running.set(false);
            shutdown();
            throw e;
        }
    }

    /**
     * Stops the tunnel server.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        LOG.info("Stopping JoltVM Tunnel Server...");
        correlator.cancelAll("Server shutting down");
        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        shutdown();
        LOG.info("JoltVM Tunnel Server stopped");
    }

    public boolean isRunning() { return running.get(); }
    public int getPort() { return port; }
    public AgentRegistry getRegistry() { return registry; }
    public RequestCorrelator getCorrelator() { return correlator; }
    public String getVersion() { return version; }

    private void shutdown() {
        if (bossGroup != null) bossGroup.shutdownGracefully();
        if (workerGroup != null) workerGroup.shutdownGracefully();
    }

    private static SslContext buildSslContext(String certPath, String keyPath) {
        if (certPath == null || keyPath == null) return null;
        try {
            return SslContextBuilder.forServer(new File(certPath), new File(keyPath)).build();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to build TLS context from cert=" + certPath + " key=" + keyPath, e);
        }
    }

    private static String loadVersion() {
        try (InputStream is = TunnelServer.class.getClassLoader()
                .getResourceAsStream("tunnel-version.properties")) {
            if (is != null) {
                Properties props = new Properties();
                props.load(is);
                return props.getProperty("projectVersion", "unknown");
            }
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }
}
