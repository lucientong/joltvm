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

package com.joltvm.server;

import com.joltvm.server.security.Role;
import com.joltvm.server.security.RoutePermissions;
import com.joltvm.server.security.SecurityConfig;
import com.joltvm.server.security.TokenService;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty channel handler that dispatches incoming HTTP requests to the
 * registered {@link RouteHandler} via the {@link HttpRouter}.
 *
 * <p>Handles CORS preflight (OPTIONS) requests automatically, enforces
 * token-based authentication and RBAC when security is enabled, and provides
 * error handling for unmatched routes and handler exceptions.
 *
 * <p>Injects a synthetic {@code "_ip"} path parameter with the client's remote
 * IP address so handlers (e.g., {@code LoginHandler}) can perform IP-based checks.
 *
 * <p>When no API route matches a GET request, the handler falls back to
 * the optional static file handler (Web UI) if one is configured.
 */
final class HttpDispatcherHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = Logger.getLogger(HttpDispatcherHandler.class.getName());

    /** Synthetic path-param key carrying the client remote IP. Handlers access it via {@code pathParams.get("_ip")}. */
    static final String REMOTE_IP_PARAM = "_ip";

    private final HttpRouter router;
    private final RouteHandler staticFileHandler;
    private final SecurityConfig securityConfig;
    private final TokenService tokenService;

    HttpDispatcherHandler(HttpRouter router) {
        this(router, null, null, null);
    }

    HttpDispatcherHandler(HttpRouter router, RouteHandler staticFileHandler) {
        this(router, staticFileHandler, null, null);
    }

    HttpDispatcherHandler(HttpRouter router, RouteHandler staticFileHandler,
                          SecurityConfig securityConfig, TokenService tokenService) {
        this.router = router;
        this.staticFileHandler = staticFileHandler;
        this.securityConfig = securityConfig;
        this.tokenService = tokenService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        // Handle CORS preflight
        if (request.method().equals(HttpMethod.OPTIONS)) {
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS,
                    "GET, POST, PUT, DELETE, OPTIONS");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS,
                    "Content-Type, Authorization");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "3600");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            sendResponse(ctx, request, response);
            return;
        }

        // Extract path without query string
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();

        // Extract client IP for rate limiting and audit
        String remoteIp = extractRemoteIp(ctx, request);

        // ── Authentication & Authorization ──
        if (securityConfig != null && securityConfig.isEnabled()) {
            Role requiredRole = RoutePermissions.getRequiredRole(
                    request.method().name(), path);
            if (requiredRole != null) {
                // Extract Bearer token
                String authHeader = request.headers().get("Authorization");
                if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                    FullHttpResponse response = HttpResponseHelper.error(
                            HttpResponseStatus.UNAUTHORIZED,
                            "Authentication required. Provide Authorization: Bearer <token>");
                    sendResponse(ctx, request, response);
                    return;
                }
                String token = authHeader.substring(7);
                TokenService.TokenInfo tokenInfo = tokenService.validateToken(token);
                if (tokenInfo == null) {
                    FullHttpResponse response = HttpResponseHelper.error(
                            HttpResponseStatus.UNAUTHORIZED,
                            "Invalid or expired token");
                    sendResponse(ctx, request, response);
                    return;
                }
                if (!tokenInfo.role().hasPermission(requiredRole)) {
                    FullHttpResponse response = HttpResponseHelper.error(
                            HttpResponseStatus.FORBIDDEN,
                            "Insufficient permissions. Required: " + requiredRole.name()
                                    + ", current: " + tokenInfo.role().name());
                    sendResponse(ctx, request, response);
                    return;
                }
            }
        }

        // Route matching
        HttpRouter.RouteMatch match = router.match(request.method(), path);
        if (match == null) {
            // Fallback to static file handler for GET requests (Web UI)
            if (staticFileHandler != null && request.method().equals(HttpMethod.GET)) {
                try {
                    String filePath = path.length() > 1 ? path.substring(1) : "";
                    FullHttpResponse response = staticFileHandler.handle(request,
                            filePath.isEmpty()
                                    ? Collections.emptyMap()
                                    : Collections.singletonMap("filePath", filePath));
                    sendResponse(ctx, request, response);
                    return;
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Error serving static file: " + path, e);
                }
            }
            FullHttpResponse response = HttpResponseHelper.notFound(
                    "No route found for " + request.method() + " " + path);
            sendResponse(ctx, request, response);
            return;
        }

        // Inject synthetic IP param so handlers can use it for rate limiting / audit
        Map<String, String> enrichedParams = new HashMap<>(match.pathParams());
        enrichedParams.put(REMOTE_IP_PARAM, remoteIp);

        // Execute handler
        try {
            FullHttpResponse response = match.handler().handle(request, enrichedParams);
            sendResponse(ctx, request, response);
        } catch (Exception e) {
            String errorId = UUID.randomUUID().toString();
            LOG.log(Level.SEVERE,
                    "Error handling request [errorId=" + errorId + "]: "
                            + request.method() + " " + path, e);
            FullHttpResponse response = HttpResponseHelper.serverError(
                    "Internal server error (id: " + errorId + ")");
            sendResponse(ctx, request, response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "Channel exception", cause);
        ctx.close();
    }

    private void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request,
                              FullHttpResponse response) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Extracts the client IP, preferring {@code X-Forwarded-For} when behind a proxy.
     */
    private static String extractRemoteIp(ChannelHandlerContext ctx, FullHttpRequest request) {
        String xff = request.headers().get("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        try {
            InetSocketAddress addr = (InetSocketAddress) ctx.channel().remoteAddress();
            return addr != null ? addr.getAddress().getHostAddress() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}
