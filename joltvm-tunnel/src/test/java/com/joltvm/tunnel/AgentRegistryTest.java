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

import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AgentRegistryTest {

    private AgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentRegistry();
    }

    @Test
    void testRegisterAndGetAgent() {
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.remoteAddress()).thenReturn(null);

        AgentRegistry.AgentInfo info = registry.register("agent-1", channel,
                Map.of("hostname", "host1", "pid", "1234"));

        assertNotNull(info);
        assertEquals("agent-1", info.agentId());
        assertEquals(channel, info.channel());
        assertEquals("host1", info.metadata().get("hostname"));
        assertTrue(info.connectedAt() > 0);

        AgentRegistry.AgentInfo fetched = registry.getAgent("agent-1");
        assertEquals(info.agentId(), fetched.agentId());
    }

    @Test
    void testGetAgentCount() {
        assertEquals(0, registry.getAgentCount());

        Channel ch1 = mock(Channel.class);
        Channel ch2 = mock(Channel.class);
        registry.register("a1", ch1, Map.of());
        registry.register("a2", ch2, Map.of());

        assertEquals(2, registry.getAgentCount());
    }

    @Test
    void testUnregisterByChannel() {
        Channel channel = mock(Channel.class);
        registry.register("agent-1", channel, Map.of());
        assertEquals(1, registry.getAgentCount());

        AgentRegistry.AgentInfo removed = registry.unregisterByChannel(channel);
        assertNotNull(removed);
        assertEquals("agent-1", removed.agentId());
        assertEquals(0, registry.getAgentCount());
    }

    @Test
    void testUnregisterUnknownChannel() {
        Channel channel = mock(Channel.class);
        assertNull(registry.unregisterByChannel(channel));
    }

    @Test
    void testUpdateHeartbeat() throws InterruptedException {
        Channel channel = mock(Channel.class);
        registry.register("agent-1", channel, Map.of());

        long before = registry.getAgent("agent-1").lastHeartbeat();
        Thread.sleep(10);
        registry.updateHeartbeat("agent-1");
        long after = registry.getAgent("agent-1").lastHeartbeat();

        assertTrue(after >= before);
    }

    @Test
    void testGetAgentId() {
        Channel channel = mock(Channel.class);
        registry.register("agent-1", channel, Map.of());

        assertEquals("agent-1", registry.getAgentId(channel));
        assertNull(registry.getAgentId(mock(Channel.class)));
    }

    @Test
    void testTokenValidation() {
        // No tokens = allow all (dev mode)
        assertTrue(registry.isValidToken("anything"));
        assertTrue(registry.isValidToken(null));

        // Add token
        registry.addToken("secret-token");
        assertTrue(registry.isValidToken("secret-token"));
        assertFalse(registry.isValidToken("wrong-token"));
        assertFalse(registry.isValidToken(null));
    }

    @Test
    void testToList() {
        Channel ch = mock(Channel.class);
        when(ch.isActive()).thenReturn(true);
        when(ch.remoteAddress()).thenReturn(null);

        registry.register("agent-1", ch, Map.of("hostname", "h1"));
        var list = registry.toList();

        assertEquals(1, list.size());
        assertEquals("agent-1", list.get(0).get("agentId"));
    }

    @Test
    void testGetAllAgents() {
        Channel ch = mock(Channel.class);
        registry.register("a1", ch, Map.of());

        Map<String, AgentRegistry.AgentInfo> all = registry.getAllAgents();
        assertEquals(1, all.size());
        assertTrue(all.containsKey("a1"));
    }

    @Test
    void testAgentInfoToMap() {
        Channel ch = mock(Channel.class);
        when(ch.isActive()).thenReturn(true);
        when(ch.remoteAddress()).thenReturn(null);

        AgentRegistry.AgentInfo info = new AgentRegistry.AgentInfo(
                "agent-1", ch, Map.of("hostname", "h1"), 1000L, 2000L);

        Map<String, Object> map = info.toMap();
        assertEquals("agent-1", map.get("agentId"));
        assertEquals(true, map.get("connected"));
        assertEquals(1000L, map.get("connectedAt"));
        assertEquals(2000L, map.get("lastHeartbeat"));
    }

    @Test
    void testAgentInfoWithHeartbeat() {
        Channel ch = mock(Channel.class);
        AgentRegistry.AgentInfo info = new AgentRegistry.AgentInfo(
                "agent-1", ch, Map.of(), 1000L, 1000L);
        AgentRegistry.AgentInfo updated = info.withHeartbeat(2000L);

        assertEquals(2000L, updated.lastHeartbeat());
        assertEquals(1000L, updated.connectedAt());
        assertEquals("agent-1", updated.agentId());
    }
}
