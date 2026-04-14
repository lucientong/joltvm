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

import com.joltvm.server.HttpResponseHelper;
import com.joltvm.server.RouteHandler;
import com.joltvm.server.hotswap.HotSwapRecord;
import com.joltvm.server.hotswap.HotSwapService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handler for {@code GET /api/hotswap/history} — returns the hot-swap operation history.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code limit} — maximum number of records to return (default: 50)</li>
 * </ul>
 *
 * <p>Response format:
 * <pre>
 * {
 *   "records": [...],
 *   "total": 10,
 *   "rollbackable": ["com.example.MyClass"]
 * }
 * </pre>
 */
public class HotSwapHistoryHandler implements RouteHandler {

    private final HotSwapService hotSwapService;

    public HotSwapHistoryHandler(HotSwapService hotSwapService) {
        this.hotSwapService = hotSwapService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        int limit = 50;
        List<String> limitParams = decoder.parameters().get("limit");
        if (limitParams != null && !limitParams.isEmpty()) {
            try {
                limit = Math.max(1, Math.min(200, Integer.parseInt(limitParams.get(0))));
            } catch (NumberFormatException ignored) {
                // Use default
            }
        }

        List<HotSwapRecord> records = hotSwapService.getHistory(limit);
        Set<String> rollbackable = hotSwapService.getRollbackableClasses();

        // Convert records to maps for JSON serialization
        List<Map<String, Object>> recordMaps = records.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("className", r.className());
            m.put("action", r.action().name());
            m.put("status", r.status().name());
            m.put("message", r.message());
            m.put("timestamp", r.timestamp().toString());
            if (r.operator() != null) m.put("operator", r.operator());
            if (r.reason() != null) m.put("reason", r.reason());
            if (r.diff() != null) m.put("diff", r.diff());
            return m;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", recordMaps);
        response.put("total", hotSwapService.getHistory().size());
        response.put("rollbackable", rollbackable);

        return HttpResponseHelper.json(response);
    }
}
