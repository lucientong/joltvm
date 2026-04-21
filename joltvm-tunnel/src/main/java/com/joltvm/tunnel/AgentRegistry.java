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

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry of connected agents.
 *
 * <p>Tracks agent connections, metadata, and provides lookup by agent ID.
 * Thread-safe: all operations are safe for concurrent access from multiple Netty I/O threads.
 */
public class AgentRegistry {

    /**
     * Agent connection info.
     *
     * @param agentId   unique identifier
     * @param channel   the Netty channel to this agent
     * @param metadata  agent-supplied metadata (hostname, pid, etc.)
     * @param connectedAt timestamp of connection
     * @param lastHeartbeat timestamp of last heartbeat
     */
    public record AgentInfo(
            String agentId,
            Channel channel,
            Map<String, String> metadata,
            long connectedAt,
            long lastHeartbeat
    ) {
        public AgentInfo {
            metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        /** Returns agent info as a serializable map (without the Channel). */
        public Map<String, Object> toMap() {
            return Map.of(
                    "agentId", agentId,
                    "metadata", metadata,
                    "connectedAt", connectedAt,
                    "lastHeartbeat", lastHeartbeat,
                    "connected", channel != null && channel.isActive(),
                    "remoteAddress", channel != null && channel.remoteAddress() != null
                            ? channel.remoteAddress().toString() : "unknown"
            );
        }

        /** Creates a copy with updated heartbeat timestamp. */
        public AgentInfo withHeartbeat(long timestamp) {
            return new AgentInfo(agentId, channel, metadata, connectedAt, timestamp);
        }
    }

    /** agentId → AgentInfo */
    private final ConcurrentHashMap<String, AgentInfo> agents = new ConcurrentHashMap<>();

    /** channel → agentId (reverse index for disconnect handling) */
    private final ConcurrentHashMap<Channel, String> channelToAgent = new ConcurrentHashMap<>();

    /** Pre-shared registration tokens. Empty = allow all (dev mode). */
    private final ConcurrentHashMap<String, Boolean> validTokens = new ConcurrentHashMap<>();

    /**
     * Adds a valid registration token.
     *
     * @param token the pre-shared token
     */
    public void addToken(String token) {
        validTokens.put(token, Boolean.TRUE);
    }

    /**
     * Validates a registration token.
     *
     * @param token the token to validate
     * @return true if valid (or if no tokens are configured)
     */
    public boolean isValidToken(String token) {
        if (validTokens.isEmpty()) return true; // dev mode: allow all
        return token != null && validTokens.containsKey(token);
    }

    /**
     * Registers an agent connection.
     *
     * @param agentId  the agent ID
     * @param channel  the WebSocket channel
     * @param metadata agent metadata
     * @return the registered AgentInfo
     */
    public AgentInfo register(String agentId, Channel channel, Map<String, String> metadata) {
        long now = System.currentTimeMillis();
        AgentInfo info = new AgentInfo(agentId, channel, metadata, now, now);
        agents.put(agentId, info);
        channelToAgent.put(channel, agentId);
        return info;
    }

    /**
     * Removes an agent by its channel (called on disconnect).
     *
     * @param channel the disconnected channel
     * @return the removed AgentInfo, or null if not found
     */
    public AgentInfo unregisterByChannel(Channel channel) {
        String agentId = channelToAgent.remove(channel);
        if (agentId != null) {
            return agents.remove(agentId);
        }
        return null;
    }

    /**
     * Updates the heartbeat timestamp for an agent.
     *
     * @param agentId the agent ID
     */
    public void updateHeartbeat(String agentId) {
        AgentInfo info = agents.get(agentId);
        if (info != null) {
            agents.put(agentId, info.withHeartbeat(System.currentTimeMillis()));
        }
    }

    /**
     * Returns the agent info for the given ID.
     *
     * @param agentId the agent ID
     * @return the AgentInfo, or null if not registered
     */
    public AgentInfo getAgent(String agentId) {
        return agents.get(agentId);
    }

    /**
     * Returns the agent ID for the given channel.
     *
     * @param channel the channel
     * @return the agent ID, or null
     */
    public String getAgentId(Channel channel) {
        return channelToAgent.get(channel);
    }

    /**
     * Returns all registered agents.
     *
     * @return unmodifiable map of agentId → AgentInfo
     */
    public Map<String, AgentInfo> getAllAgents() {
        return Collections.unmodifiableMap(agents);
    }

    /**
     * Returns the number of connected agents.
     *
     * @return agent count
     */
    public int getAgentCount() {
        return agents.size();
    }

    /**
     * Returns all agents as a serializable list of maps.
     *
     * @return list of agent info maps
     */
    public java.util.List<Map<String, Object>> toList() {
        return agents.values().stream()
                .map(AgentInfo::toMap)
                .collect(Collectors.toList());
    }
}
