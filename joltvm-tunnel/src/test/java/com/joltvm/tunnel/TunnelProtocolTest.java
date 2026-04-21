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

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TunnelProtocolTest {

    @Test
    void testCreateRegister() {
        String json = TunnelProtocol.createRegister("agent-1", "secret",
                Map.of("hostname", "host1", "pid", "1234"));
        JsonObject msg = TunnelProtocol.parse(json);
        assertNotNull(msg);
        assertEquals("register", TunnelProtocol.getType(msg));
        assertEquals("agent-1", msg.get("agentId").getAsString());
        assertEquals("secret", msg.get("token").getAsString());
        assertTrue(msg.has("metadata"));
    }

    @Test
    void testCreateRegistered() {
        String json = TunnelProtocol.createRegistered("agent-1");
        JsonObject msg = TunnelProtocol.parse(json);
        assertEquals("registered", TunnelProtocol.getType(msg));
        assertEquals("agent-1", msg.get("agentId").getAsString());
    }

    @Test
    void testCreateHeartbeat() {
        String json = TunnelProtocol.createHeartbeat();
        JsonObject msg = TunnelProtocol.parse(json);
        assertEquals("heartbeat", TunnelProtocol.getType(msg));
        assertTrue(msg.has("timestamp"));
    }

    @Test
    void testCreateHeartbeatAck() {
        String json = TunnelProtocol.createHeartbeatAck();
        JsonObject msg = TunnelProtocol.parse(json);
        assertEquals("heartbeat_ack", TunnelProtocol.getType(msg));
    }

    @Test
    void testCreateRequest() {
        String json = TunnelProtocol.createRequest("req-1", "GET", "/api/health",
                Map.of("Accept", "application/json"), null);
        JsonObject msg = TunnelProtocol.parse(json);
        assertEquals("request", TunnelProtocol.getType(msg));
        assertEquals("req-1", msg.get("requestId").getAsString());
        assertEquals("GET", msg.get("method").getAsString());
        assertEquals("/api/health", msg.get("path").getAsString());
        assertFalse(msg.has("body"));
    }

    @Test
    void testCreateRequestWithBody() {
        String json = TunnelProtocol.createRequest("req-2", "POST", "/api/hotswap",
                Map.of("Content-Type", "application/json"), "{\"code\":\"test\"}");
        JsonObject msg = TunnelProtocol.parse(json);
        assertEquals("req-2", msg.get("requestId").getAsString());
        assertEquals("{\"code\":\"test\"}", msg.get("body").getAsString());
    }

    @Test
    void testCreateResponse() {
        String json = TunnelProtocol.createResponse("req-1", 200,
                Map.of("Content-Type", "application/json"), "{\"status\":\"UP\"}");
        JsonObject msg = TunnelProtocol.parse(json);
        assertEquals("response", TunnelProtocol.getType(msg));
        assertEquals("req-1", msg.get("requestId").getAsString());
        assertEquals(200, msg.get("status").getAsInt());
        assertEquals("{\"status\":\"UP\"}", msg.get("body").getAsString());
    }

    @Test
    void testCreateError() {
        String json = TunnelProtocol.createError("Something went wrong");
        JsonObject msg = TunnelProtocol.parse(json);
        assertEquals("error", TunnelProtocol.getType(msg));
        assertEquals("Something went wrong", msg.get("message").getAsString());
    }

    @Test
    void testParseInvalidJson() {
        assertNull(TunnelProtocol.parse("not json"));
        assertNull(TunnelProtocol.parse(""));
        assertNull(TunnelProtocol.parse(null));
    }

    @Test
    void testGetTypeNullOrMissing() {
        assertNull(TunnelProtocol.getType(null));
        assertNull(TunnelProtocol.getType(new JsonObject()));
    }

    @Test
    void testNewRequestIdUnique() {
        String id1 = TunnelProtocol.newRequestId();
        String id2 = TunnelProtocol.newRequestId();
        assertNotNull(id1);
        assertNotNull(id2);
        assertNotEquals(id1, id2);
        assertEquals(16, id1.length());
    }
}
