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

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Netty channel handler that dispatches incoming HTTP requests to the
 * registered {@link RouteHandler} via the {@link HttpRouter}.
 *
 * <p>Handles CORS preflight (OPTIONS) requests automatically and provides
 * error handling for unmatched routes and handler exceptions.
 */
final class HttpDispatcherHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger LOG = Logger.getLogger(HttpDispatcherHandler.class.getName());

    private final HttpRouter router;

    HttpDispatcherHandler(HttpRouter router) {
        this.router = router;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        // Handle CORS preflight
        if (request.method().equals(HttpMethod.OPTIONS)) {
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE, "3600");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
            sendResponse(ctx, request, response);
            return;
        }

        // Extract path without query string
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String path = decoder.path();

        // Route matching
        HttpRouter.RouteMatch match = router.match(request.method(), path);
        if (match == null) {
            FullHttpResponse response = HttpResponseHelper.notFound(
                    "No route found for " + request.method() + " " + path);
            sendResponse(ctx, request, response);
            return;
        }

        // Execute handler
        try {
            FullHttpResponse response = match.handler().handle(request, match.pathParams());
            sendResponse(ctx, request, response);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error handling request: " + request.method() + " " + path, e);
            FullHttpResponse response = HttpResponseHelper.serverError(
                    "Internal server error: " + e.getMessage());
            sendResponse(ctx, request, response);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "Channel exception", cause);
        ctx.close();
    }

    private void sendResponse(ChannelHandlerContext ctx, FullHttpRequest request, FullHttpResponse response) {
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            ctx.writeAndFlush(response);
        } else {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
