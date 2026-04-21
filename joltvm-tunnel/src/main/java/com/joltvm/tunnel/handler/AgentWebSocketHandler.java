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
import com.google.gson.JsonObject;
import com.joltvm.tunnel.AgentRegistry;
import com.joltvm.tunnel.RequestCorrelator;
import com.joltvm.tunnel.TunnelProtocol;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles WebSocket frames from connected agents on the tunnel server side.
 *
 * <p>Processes registration, heartbeats, and proxied responses from agents.
 */
public class AgentWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger LOG = Logger.getLogger(AgentWebSocketHandler.class.getName());
    private static final Gson GSON = new Gson();

    private final AgentRegistry registry;
    private final RequestCorrelator correlator;

    public AgentWebSocketHandler(AgentRegistry registry, RequestCorrelator correlator) {
        this.registry = registry;
        this.correlator = correlator;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    TunnelProtocol.createError("Only text frames are supported")));
            return;
        }

        String text = textFrame.text();
        JsonObject msg = TunnelProtocol.parse(text);
        if (msg == null) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    TunnelProtocol.createError("Invalid JSON")));
            return;
        }

        String type = TunnelProtocol.getType(msg);
        if (type == null) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    TunnelProtocol.createError("Missing 'type' field")));
            return;
        }

        switch (type) {
            case TunnelProtocol.TYPE_REGISTER -> handleRegister(ctx, msg);
            case TunnelProtocol.TYPE_HEARTBEAT -> handleHeartbeat(ctx);
            case TunnelProtocol.TYPE_RESPONSE -> handleResponse(msg);
            default -> ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    TunnelProtocol.createError("Unknown message type: " + type)));
        }
    }

    private void handleRegister(ChannelHandlerContext ctx, JsonObject msg) {
        String agentId = msg.has("agentId") ? msg.get("agentId").getAsString() : null;
        String token = msg.has("token") ? msg.get("token").getAsString() : null;

        if (agentId == null || agentId.isBlank()) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    TunnelProtocol.createError("Missing or empty 'agentId'")));
            return;
        }

        if (!registry.isValidToken(token)) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame(
                    TunnelProtocol.createError("Invalid registration token")));
            ctx.close();
            return;
        }

        // Parse metadata
        @SuppressWarnings("unchecked")
        Map<String, String> metadata = msg.has("metadata")
                ? GSON.fromJson(msg.get("metadata"), Map.class)
                : Map.of();

        registry.register(agentId, ctx.channel(), metadata);
        ctx.channel().writeAndFlush(new TextWebSocketFrame(
                TunnelProtocol.createRegistered(agentId)));

        LOG.info("Agent registered: " + agentId + " from " + ctx.channel().remoteAddress());
    }

    private void handleHeartbeat(ChannelHandlerContext ctx) {
        String agentId = registry.getAgentId(ctx.channel());
        if (agentId != null) {
            registry.updateHeartbeat(agentId);
        }
        ctx.channel().writeAndFlush(new TextWebSocketFrame(
                TunnelProtocol.createHeartbeatAck()));
    }

    @SuppressWarnings("unchecked")
    private void handleResponse(JsonObject msg) {
        String requestId = msg.has("requestId") ? msg.get("requestId").getAsString() : null;
        if (requestId == null) {
            LOG.warning("Received response without requestId");
            return;
        }

        int status = msg.has("status") ? msg.get("status").getAsInt() : 500;
        Map<String, String> headers = msg.has("headers")
                ? GSON.fromJson(msg.get("headers"), Map.class)
                : Map.of();
        String body = msg.has("body") ? msg.get("body").getAsString() : null;

        correlator.completeRequest(requestId,
                new RequestCorrelator.ProxiedResponse(requestId, status, headers, body));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        AgentRegistry.AgentInfo removed = registry.unregisterByChannel(ctx.channel());
        if (removed != null) {
            LOG.info("Agent disconnected: " + removed.agentId());
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        LOG.log(Level.WARNING, "Agent WebSocket error: " + ctx.channel().remoteAddress(), cause);
        ctx.close();
    }
}
