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
import com.joltvm.server.tracing.MethodTraceService;
import com.joltvm.server.tracing.TraceRecord;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/trace/records} — returns captured trace records.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code limit} — maximum number of records to return (default: 100, max: 500)</li>
 * </ul>
 *
 * <p>Response format:
 * <pre>
 * {
 *   "records": [ { "id": "...", "className": "...", ... } ],
 *   "total": 42,
 *   "tracing": true
 * }
 * </pre>
 */
public class TraceListHandler implements RouteHandler {

    private final MethodTraceService traceService;

    public TraceListHandler(MethodTraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        int limit = 100;
        List<String> limitParams = decoder.parameters().get("limit");
        if (limitParams != null && !limitParams.isEmpty()) {
            try {
                limit = Math.max(1, Math.min(500, Integer.parseInt(limitParams.get(0))));
            } catch (NumberFormatException ignored) {
                // Use default
            }
        }

        List<TraceRecord> records = traceService.getCollector().getRecords(limit);

        // Convert records to maps for JSON serialization
        List<Map<String, Object>> recordMaps = records.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("className", r.className());
            m.put("methodName", r.methodName());
            m.put("signature", r.signature());
            m.put("parameterTypes", r.parameterTypes());
            m.put("arguments", r.arguments());
            m.put("returnValue", r.returnValue());
            m.put("exceptionType", r.exceptionType());
            m.put("exceptionMessage", r.exceptionMessage());
            m.put("durationNanos", r.durationNanos());
            m.put("durationMs", r.durationMs());
            m.put("threadName", r.threadName());
            m.put("threadId", r.threadId());
            m.put("timestamp", r.timestamp().toString());
            m.put("depth", r.depth());
            m.put("hasException", r.hasException());
            return m;
        }).toList();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", recordMaps);
        response.put("total", traceService.getCollector().getRecordCount());
        response.put("tracing", traceService.isTracing());

        return HttpResponseHelper.json(response);
    }
}
