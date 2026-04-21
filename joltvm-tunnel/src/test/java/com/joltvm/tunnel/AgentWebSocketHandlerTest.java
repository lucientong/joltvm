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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.joltvm.tunnel.handler.AgentWebSocketHandler;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentWebSocketHandlerTest {

    private static final Gson GSON = new Gson();
    private AgentRegistry registry;
    private RequestCorrelator correlator;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistry();
        correlator = new RequestCorrelator();
        channel = new EmbeddedChannel(new AgentWebSocketHandler(registry, correlator));
    }

    @Test
    void testRegisterAgent() {
        String json = TunnelProtocol.createRegister("agent-1", "",
                Map.of("hostname", "h1", "pid", "1234"));
        channel.writeInbound(new TextWebSocketFrame(json));

        // Should have registered
        assertEquals(1, registry.getAgentCount());
        assertNotNull(registry.getAgent("agent-1"));

        // Should have sent "registered" response
        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        JsonObject msg = GSON.fromJson(response.text(), JsonObject.class);
        assertEquals("registered", msg.get("type").getAsString());
        assertEquals("agent-1", msg.get("agentId").getAsString());
        response.release();
    }

    @Test
    void testRegisterWithInvalidToken() {
        registry.addToken("valid-token");

        String json = TunnelProtocol.createRegister("agent-1", "wrong-token", Map.of());
        channel.writeInbound(new TextWebSocketFrame(json));

        // Should not have registered
        assertEquals(0, registry.getAgentCount());

        // Should have sent error
        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        JsonObject msg = GSON.fromJson(response.text(), JsonObject.class);
        assertEquals("error", msg.get("type").getAsString());
        assertTrue(msg.get("message").getAsString().contains("Invalid registration token"));
        response.release();
    }

    @Test
    void testRegisterWithEmptyAgentId() {
        String json = GSON.toJson(Map.of("type", "register", "agentId", "", "token", ""));
        channel.writeInbound(new TextWebSocketFrame(json));

        assertEquals(0, registry.getAgentCount());

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        JsonObject msg = GSON.fromJson(response.text(), JsonObject.class);
        assertEquals("error", msg.get("type").getAsString());
        response.release();
    }

    @Test
    void testHeartbeat() {
        // First register
        String registerJson = TunnelProtocol.createRegister("agent-1", "", Map.of());
        channel.writeInbound(new TextWebSocketFrame(registerJson));
        channel.readOutbound(); // consume registration response

        // Send heartbeat
        String heartbeatJson = TunnelProtocol.createHeartbeat();
        channel.writeInbound(new TextWebSocketFrame(heartbeatJson));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        JsonObject msg = GSON.fromJson(response.text(), JsonObject.class);
        assertEquals("heartbeat_ack", msg.get("type").getAsString());
        response.release();
    }

    @Test
    void testResponse() {
        // Register a pending request
        var future = correlator.registerRequest("req-123");

        // Agent sends response
        String responseJson = TunnelProtocol.createResponse("req-123", 200,
                Map.of("Content-Type", "application/json"), "{\"status\":\"UP\"}");
        channel.writeInbound(new TextWebSocketFrame(responseJson));

        assertTrue(future.isDone());
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    void testInvalidJson() {
        channel.writeInbound(new TextWebSocketFrame("not json {{{"));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        JsonObject msg = GSON.fromJson(response.text(), JsonObject.class);
        assertEquals("error", msg.get("type").getAsString());
        response.release();
    }

    @Test
    void testMissingType() {
        channel.writeInbound(new TextWebSocketFrame("{\"foo\":\"bar\"}"));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        JsonObject msg = GSON.fromJson(response.text(), JsonObject.class);
        assertEquals("error", msg.get("type").getAsString());
        response.release();
    }

    @Test
    void testUnknownType() {
        channel.writeInbound(new TextWebSocketFrame("{\"type\":\"unknown_xyz\"}"));

        TextWebSocketFrame response = channel.readOutbound();
        assertNotNull(response);
        JsonObject msg = GSON.fromJson(response.text(), JsonObject.class);
        assertEquals("error", msg.get("type").getAsString());
        response.release();
    }

    @Test
    void testChannelInactiveUnregisters() {
        String json = TunnelProtocol.createRegister("agent-1", "", Map.of());
        channel.writeInbound(new TextWebSocketFrame(json));
        channel.readOutbound(); // consume response
        assertEquals(1, registry.getAgentCount());

        // Simulate disconnect
        channel.close();
        assertEquals(0, registry.getAgentCount());
    }
}
