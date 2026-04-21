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

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TunnelClientTest {

    @Test
    void testFromAgentArgsNoTunnelServer() {
        assertNull(TunnelClient.fromAgentArgs(Map.of(), 7758));
        assertNull(TunnelClient.fromAgentArgs(Map.of("port", "7758"), 7758));
    }

    @Test
    void testFromAgentArgsWithTunnelServer() {
        Map<String, String> args = new HashMap<>();
        args.put("tunnelServer", "ws://localhost:8800/ws/agent");
        args.put("tunnelToken", "secret");
        args.put("tunnelAgentId", "my-agent");

        TunnelClient client = TunnelClient.fromAgentArgs(args, 7758);
        assertNotNull(client);
        assertEquals("my-agent", client.getAgentId());
        assertFalse(client.isConnected());
    }

    @Test
    void testFromAgentArgsDefaultAgentId() {
        Map<String, String> args = new HashMap<>();
        args.put("tunnelServer", "ws://localhost:8800/ws/agent");

        TunnelClient client = TunnelClient.fromAgentArgs(args, 7758);
        assertNotNull(client);
        assertNotNull(client.getAgentId());
        assertFalse(client.getAgentId().isBlank());
    }

    @Test
    void testStopIdempotent() {
        TunnelClient client = new TunnelClient(
                "ws://localhost:8800/ws/agent", "agent-1", "", 7758);
        // Should not throw even when never started
        client.stop();
        client.stop();
    }

    @Test
    void testFromAgentArgsBlankUrl() {
        Map<String, String> args = new HashMap<>();
        args.put("tunnelServer", "  ");

        assertNull(TunnelClient.fromAgentArgs(args, 7758));
    }
}
