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
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.Map;

/**
 * Handler for {@code GET /api/trace/flamegraph} — returns flame graph data.
 *
 * <p>Returns flame graph data in d3-flame-graph compatible JSON format:
 * <pre>
 * {
 *   "name": "root",
 *   "value": 100,
 *   "children": [
 *     { "name": "com.example.Service#handleRequest", "value": 80, "children": [...] }
 *   ]
 * }
 * </pre>
 *
 * <p>The data source depends on what tracing mode was used:
 * <ul>
 *   <li>If stack samples are available, uses sample-based aggregation (count-based).</li>
 *   <li>Otherwise, uses trace-record-based aggregation (duration in microseconds).</li>
 * </ul>
 */
public class TraceFlameGraphHandler implements RouteHandler {

    private final MethodTraceService traceService;

    public TraceFlameGraphHandler(MethodTraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        Map<String, Object> flameGraphData = traceService.getCollector().getFlameGraphData();
        return HttpResponseHelper.json(flameGraphData);
    }
}
