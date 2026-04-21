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

import java.util.Map;
import java.util.UUID;

/**
 * Defines the tunnel WebSocket protocol used between agents and the tunnel server.
 *
 * <p>Message types:
 * <ul>
 *   <li>{@code register} — Agent → Server: register this agent</li>
 *   <li>{@code registered} — Server → Agent: registration confirmed</li>
 *   <li>{@code heartbeat} — Agent → Server: keep-alive ping</li>
 *   <li>{@code heartbeat_ack} — Server → Agent: keep-alive pong</li>
 *   <li>{@code request} — Server → Agent: proxied HTTP request</li>
 *   <li>{@code response} — Agent → Server: proxied HTTP response</li>
 *   <li>{@code error} — Either direction: error message</li>
 * </ul>
 */
public final class TunnelProtocol {

    private static final Gson GSON = new Gson();

    /** Message type constants. */
    public static final String TYPE_REGISTER = "register";
    public static final String TYPE_REGISTERED = "registered";
    public static final String TYPE_HEARTBEAT = "heartbeat";
    public static final String TYPE_HEARTBEAT_ACK = "heartbeat_ack";
    public static final String TYPE_REQUEST = "request";
    public static final String TYPE_RESPONSE = "response";
    public static final String TYPE_ERROR = "error";

    private TunnelProtocol() {}

    /**
     * Creates a registration message from agent to tunnel server.
     *
     * @param agentId   unique agent identifier
     * @param token     pre-shared registration token
     * @param metadata  agent metadata (hostname, pid, version, etc.)
     * @return JSON string
     */
    public static String createRegister(String agentId, String token, Map<String, String> metadata) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_REGISTER);
        msg.addProperty("agentId", agentId);
        msg.addProperty("token", token);
        msg.add("metadata", GSON.toJsonTree(metadata));
        return GSON.toJson(msg);
    }

    /**
     * Creates a registration confirmation from tunnel server to agent.
     *
     * @param agentId the confirmed agent ID
     * @return JSON string
     */
    public static String createRegistered(String agentId) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_REGISTERED);
        msg.addProperty("agentId", agentId);
        return GSON.toJson(msg);
    }

    /**
     * Creates a heartbeat message.
     *
     * @return JSON string
     */
    public static String createHeartbeat() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_HEARTBEAT);
        msg.addProperty("timestamp", System.currentTimeMillis());
        return GSON.toJson(msg);
    }

    /**
     * Creates a heartbeat acknowledgment.
     *
     * @return JSON string
     */
    public static String createHeartbeatAck() {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_HEARTBEAT_ACK);
        msg.addProperty("timestamp", System.currentTimeMillis());
        return GSON.toJson(msg);
    }

    /**
     * Creates a proxied HTTP request message from tunnel server to agent.
     *
     * @param requestId unique ID for correlating request/response
     * @param method    HTTP method (GET, POST, etc.)
     * @param path      the URI path (e.g., /api/health)
     * @param headers   HTTP headers as key-value map
     * @param body      request body (may be null)
     * @return JSON string
     */
    public static String createRequest(String requestId, String method, String path,
                                        Map<String, String> headers, String body) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_REQUEST);
        msg.addProperty("requestId", requestId);
        msg.addProperty("method", method);
        msg.addProperty("path", path);
        msg.add("headers", GSON.toJsonTree(headers));
        if (body != null) {
            msg.addProperty("body", body);
        }
        return GSON.toJson(msg);
    }

    /**
     * Creates a proxied HTTP response message from agent to tunnel server.
     *
     * @param requestId the correlation ID from the request
     * @param status    HTTP status code
     * @param headers   HTTP response headers
     * @param body      response body (may be null)
     * @return JSON string
     */
    public static String createResponse(String requestId, int status,
                                         Map<String, String> headers, String body) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_RESPONSE);
        msg.addProperty("requestId", requestId);
        msg.addProperty("status", status);
        msg.add("headers", GSON.toJsonTree(headers));
        if (body != null) {
            msg.addProperty("body", body);
        }
        return GSON.toJson(msg);
    }

    /**
     * Creates an error message.
     *
     * @param message error description
     * @return JSON string
     */
    public static String createError(String message) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", TYPE_ERROR);
        msg.addProperty("message", message);
        return GSON.toJson(msg);
    }

    /**
     * Generates a unique request ID.
     *
     * @return a UUID string
     */
    public static String newRequestId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Parses a JSON message string into a JsonObject.
     *
     * @param json the raw JSON
     * @return parsed JsonObject, or null if invalid
     */
    public static JsonObject parse(String json) {
        try {
            return GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Extracts the message type from a parsed message.
     *
     * @param msg parsed message
     * @return the type string, or null if missing
     */
    public static String getType(JsonObject msg) {
        return msg != null && msg.has("type") ? msg.get("type").getAsString() : null;
    }
}
