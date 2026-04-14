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

package com.joltvm.server.handler;

import com.joltvm.agent.InstrumentationHolder;
import com.joltvm.server.HttpResponseHelper;
import com.joltvm.server.RouteHandler;
import com.joltvm.server.hotswap.HotSwapRecord;
import com.joltvm.server.hotswap.HotSwapService;
import com.joltvm.server.security.AuditLogService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for {@code POST /api/rollback} — rolls back a hot-swapped class to its original bytecode.
 *
 * <p>Expects a JSON request body:
 * <pre>
 * {
 *   "className": "com.example.MyClass"
 * }
 * </pre>
 */
public class RollbackHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(RollbackHandler.class.getName());

    private final HotSwapService hotSwapService;

    public RollbackHandler(HotSwapService hotSwapService) {
        this.hotSwapService = hotSwapService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!InstrumentationHolder.isAvailable()) {
            return HttpResponseHelper.error(HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Instrumentation not available");
        }

        // Parse JSON body
        String body = request.content().toString(StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Request body is required. Expected JSON with 'className' field.");
        }

        Map<?, ?> bodyMap;
        try {
            bodyMap = HttpResponseHelper.gson().fromJson(body, Map.class);
        } catch (Exception e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Invalid JSON body: " + e.getMessage());
        }

        String className = (String) bodyMap.get("className");
        if (className == null || className.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'className' is required");
        }

        String reason = bodyMap.get("reason") instanceof String r ? r : null;
        String operator = extractOperator(request);

        try {
            HotSwapRecord record = hotSwapService.rollback(className, operator, reason);

            // Record to audit log if available
            AuditLogService auditLogService = com.joltvm.server.APIRoutes.getAuditLogService();
            if (auditLogService != null) {
                auditLogService.record(record);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", record.status() == HotSwapRecord.Status.SUCCESS);
            response.put("className", className);
            response.put("action", record.action().name());
            response.put("message", record.message());
            response.put("recordId", record.id());
            response.put("timestamp", record.timestamp().toString());

            if (record.status() == HotSwapRecord.Status.SUCCESS) {
                return HttpResponseHelper.json(response);
            } else {
                return HttpResponseHelper.json(HttpResponseStatus.BAD_REQUEST, response);
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Rollback error for " + className, e);
            return HttpResponseHelper.error(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Rollback error: " + e.getMessage());
        }
    }

    /**
     * Extracts the operator username from the Authorization Bearer token.
     */
    private static String extractOperator(FullHttpRequest request) {
        String authHeader = request.headers().get("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length < 1) return null;
            String payload = new String(
                    java.util.Base64.getUrlDecoder().decode(parts[0]),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] fields = payload.split(":", 3);
            return fields.length > 0 ? fields[0] : null;
        } catch (Exception e) {
            return null;
        }
    }
}
