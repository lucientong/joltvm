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

package com.joltvm.server.websocket;

import com.joltvm.server.security.SecurityConfig;
import com.joltvm.server.security.TokenService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.ReferenceCountUtil;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

/**
 * Authenticates WebSocket upgrade requests before the handshake completes.
 *
 * <p>Must be placed in the pipeline <em>before</em>
 * {@link io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler}.
 * When security is enabled, the upgrade URI must include a valid {@code token=}
 * query parameter (validated via {@link TokenService#validateToken(String)}).
 */
public class WebSocketAuthHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = Logger.getLogger(WebSocketAuthHandler.class.getName());

    private final SecurityConfig securityConfig;
    private final TokenService tokenService;
    private final String websocketPath;

    public WebSocketAuthHandler(SecurityConfig securityConfig, TokenService tokenService) {
        this(securityConfig, tokenService, "/ws");
    }

    public WebSocketAuthHandler(SecurityConfig securityConfig, TokenService tokenService,
                                String websocketPath) {
        this.securityConfig = securityConfig;
        this.tokenService = tokenService;
        this.websocketPath = websocketPath != null ? websocketPath : "/ws";
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }

        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();

        // Only authenticate WebSocket upgrades to our WS path
        if (!isWebSocketUpgrade(request) || !pathEquals(path, websocketPath)) {
            ctx.fireChannelRead(msg);
            return;
        }

        if (securityConfig == null || !securityConfig.isEnabled()) {
            ctx.fireChannelRead(msg);
            return;
        }

        String token = extractToken(decoder);
        if (token == null || token.isBlank()
                || tokenService == null
                || tokenService.validateToken(token) == null) {
            LOG.fine("Rejecting WebSocket upgrade: missing or invalid token from "
                    + ctx.channel().remoteAddress());
            reject(ctx, request);
            return;
        }

        ctx.fireChannelRead(msg);
    }

    private static boolean isWebSocketUpgrade(FullHttpRequest request) {
        String upgrade = request.headers().get(HttpHeaderNames.UPGRADE);
        return upgrade != null && "websocket".equalsIgnoreCase(upgrade.trim());
    }

    private static boolean pathEquals(String path, String expected) {
        if (path == null) {
            return false;
        }
        if (path.equals(expected)) {
            return true;
        }
        // Tolerate trailing slash
        return (expected + "/").equals(path) || (path + "/").equals(expected);
    }

    private static String extractToken(QueryStringDecoder decoder) {
        List<String> values = decoder.parameters().get("token");
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private static void reject(ChannelHandlerContext ctx, FullHttpRequest request) {
        byte[] body = "{\"error\":\"Unauthorized\"}".getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.UNAUTHORIZED,
                Unpooled.wrappedBuffer(body));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
        ReferenceCountUtil.release(request);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
