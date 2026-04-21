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

package com.joltvm.tunnel.handler;

import com.google.gson.Gson;
import com.joltvm.tunnel.AgentRegistry;
import com.joltvm.tunnel.RequestCorrelator;
import com.joltvm.tunnel.TunnelProtocol;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles HTTP requests on the tunnel server.
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /api/tunnel/agents} — List connected agents</li>
 *   <li>{@code GET /api/tunnel/agents/{id}} — Agent detail</li>
 *   <li>{@code * /api/tunnel/agents/{id}/proxy/**} — Proxy request to agent</li>
 *   <li>{@code GET /api/tunnel/health} — Tunnel server health</li>
 * </ul>
 */
public class TunnelHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = Logger.getLogger(TunnelHttpHandler.class.getName());
    private static final Gson GSON = new Gson();

    private static final String AGENTS_PREFIX = "/api/tunnel/agents";
    private static final String PROXY_SEGMENT = "/proxy/";
    private static final String HEALTH_PATH = "/api/tunnel/health";

    private final AgentRegistry registry;
    private final RequestCorrelator correlator;
    private final String serverVersion;

    public TunnelHttpHandler(AgentRegistry registry, RequestCorrelator correlator, String serverVersion) {
        this.registry = registry;
        this.correlator = correlator;
        this.serverVersion = serverVersion;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String path = uri.contains("?") ? uri.substring(0, uri.indexOf('?')) : uri;
        HttpMethod method = request.method();

        // CORS preflight
        if (method == HttpMethod.OPTIONS) {
            sendCorsResponse(ctx, request);
            return;
        }

        try {
            if (HEALTH_PATH.equals(path) && method == HttpMethod.GET) {
                handleHealth(ctx, request);
            } else if (path.equals(AGENTS_PREFIX) && method == HttpMethod.GET) {
                handleAgentList(ctx, request);
            } else if (path.startsWith(AGENTS_PREFIX + "/")) {
                String remainder = path.substring(AGENTS_PREFIX.length() + 1);
                int proxyIdx = remainder.indexOf(PROXY_SEGMENT.substring(1));  // find "proxy/"
                if (proxyIdx > 0) {
                    // Proxy request: /api/tunnel/agents/{id}/proxy/{path}
                    String agentId = remainder.substring(0, proxyIdx - 1); // before /proxy
                    String proxyPath = "/" + remainder.substring(proxyIdx + "proxy/".length());
                    handleProxy(ctx, request, agentId, proxyPath);
                } else if (!remainder.contains("/")) {
                    // Agent detail: /api/tunnel/agents/{id}
                    handleAgentDetail(ctx, request, remainder);
                } else {
                    sendNotFound(ctx, request);
                }
            } else {
                // Try to serve tunnel dashboard
                handleDashboard(ctx, request, path);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Error handling request: " + path, e);
            sendJson(ctx, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Map.of("error", "Internal server error"));
        }
    }

    private void handleHealth(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendJson(ctx, request, HttpResponseStatus.OK, Map.of(
                "status", "UP",
                "version", serverVersion,
                "agents", registry.getAgentCount(),
                "pendingRequests", correlator.getPendingCount()
        ));
    }

    private void handleAgentList(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendJson(ctx, request, HttpResponseStatus.OK, Map.of(
                "agents", registry.toList(),
                "count", registry.getAgentCount()
        ));
    }

    private void handleAgentDetail(ChannelHandlerContext ctx, FullHttpRequest request, String agentId) {
        AgentRegistry.AgentInfo info = registry.getAgent(agentId);
        if (info == null) {
            sendJson(ctx, request, HttpResponseStatus.NOT_FOUND,
                    Map.of("error", "Agent not found: " + agentId));
            return;
        }
        sendJson(ctx, request, HttpResponseStatus.OK, info.toMap());
    }

    private void handleProxy(ChannelHandlerContext ctx, FullHttpRequest request,
                              String agentId, String proxyPath) {
        AgentRegistry.AgentInfo agent = registry.getAgent(agentId);
        if (agent == null) {
            sendJson(ctx, request, HttpResponseStatus.NOT_FOUND,
                    Map.of("error", "Agent not found: " + agentId));
            return;
        }
        if (!agent.channel().isActive()) {
            sendJson(ctx, request, HttpResponseStatus.BAD_GATEWAY,
                    Map.of("error", "Agent is not connected: " + agentId));
            return;
        }

        // Build proxied request
        String requestId = TunnelProtocol.newRequestId();
        String method = request.method().name();

        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : request.headers()) {
            headers.put(entry.getKey(), entry.getValue());
        }

        String body = null;
        if (request.content().readableBytes() > 0) {
            body = request.content().toString(StandardCharsets.UTF_8);
        }

        // Send request to agent via WebSocket
        String tunnelRequest = TunnelProtocol.createRequest(requestId, method, proxyPath, headers, body);
        agent.channel().writeAndFlush(new TextWebSocketFrame(tunnelRequest));

        // Wait for response
        CompletableFuture<RequestCorrelator.ProxiedResponse> future = correlator.registerRequest(requestId);
        future.whenComplete((response, ex) -> {
            if (ex != null) {
                sendJson(ctx, request, HttpResponseStatus.GATEWAY_TIMEOUT,
                        Map.of("error", "Agent did not respond in time"));
            } else {
                // Forward agent response back to HTTP client
                FullHttpResponse httpResponse = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(response.status()),
                        response.body() != null
                                ? Unpooled.copiedBuffer(response.body(), StandardCharsets.UTF_8)
                                : Unpooled.EMPTY_BUFFER);

                for (Map.Entry<String, String> h : response.headers().entrySet()) {
                    httpResponse.headers().set(h.getKey(), h.getValue());
                }
                addCorsHeaders(httpResponse);
                httpResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH,
                        httpResponse.content().readableBytes());

                ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE);
            }
        });
    }

    private void handleDashboard(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
        // Serve tunnel dashboard static files
        String resourcePath;
        if ("/".equals(path) || path.isEmpty()) {
            resourcePath = "tunnel-ui/index.html";
        } else {
            resourcePath = "tunnel-ui" + path;
        }

        var resource = getClass().getClassLoader().getResourceAsStream(resourcePath);
        if (resource != null) {
            try {
                byte[] content = resource.readAllBytes();
                resource.close();

                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                        Unpooled.wrappedBuffer(content));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, guessContentType(path));
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
                addCorsHeaders(response);
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                sendNotFound(ctx, request);
            }
        } else {
            sendNotFound(ctx, request);
        }
    }

    private void sendJson(ChannelHandlerContext ctx, FullHttpRequest request,
                           HttpResponseStatus status, Object data) {
        byte[] json = GSON.toJson(data).getBytes(StandardCharsets.UTF_8);
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(json));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, json.length);
        addCorsHeaders(response);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendNotFound(ChannelHandlerContext ctx, FullHttpRequest request) {
        sendJson(ctx, request, HttpResponseStatus.NOT_FOUND,
                Map.of("error", "Not found"));
    }

    private void sendCorsResponse(ChannelHandlerContext ctx, FullHttpRequest request) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.EMPTY_BUFFER);
        addCorsHeaders(response);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static void addCorsHeaders(FullHttpResponse response) {
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "86400");
    }

    private static String guessContentType(String path) {
        if (path.endsWith(".html")) return "text/html; charset=UTF-8";
        if (path.endsWith(".css")) return "text/css; charset=UTF-8";
        if (path.endsWith(".js")) return "application/javascript; charset=UTF-8";
        if (path.endsWith(".json")) return "application/json; charset=UTF-8";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        if (path.endsWith(".ico")) return "image/x-icon";
        return "application/octet-stream";
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "HTTP handler error", cause);
        ctx.close();
    }
}
