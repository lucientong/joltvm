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
import com.joltvm.server.thread.ThreadDiagnosticsService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/threads/top} — returns top-N CPU consuming threads.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code n} — number of top threads (default 10, max 100)</li>
 *   <li>{@code interval} — sampling interval in milliseconds (default 1000, min 100, max 5000)</li>
 * </ul>
 */
public class ThreadTopHandler implements RouteHandler {

    private final ThreadDiagnosticsService threadService;

    public ThreadTopHandler(ThreadDiagnosticsService threadService) {
        this.threadService = threadService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        int n = 10;
        long interval = 1000;

        try {
            List<String> nParam = decoder.parameters().get("n");
            if (nParam != null && !nParam.isEmpty()) {
                n = Integer.parseInt(nParam.get(0));
            }
            List<String> intervalParam = decoder.parameters().get("interval");
            if (intervalParam != null && !intervalParam.isEmpty()) {
                interval = Long.parseLong(intervalParam.get(0));
            }
        } catch (NumberFormatException e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Invalid numeric parameter: " + e.getMessage());
        }

        List<Map<String, Object>> topThreads = threadService.getTopThreadsByCpu(n, interval);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("threads", topThreads);
        response.put("count", topThreads.size());
        response.put("samplingIntervalMs", interval);
        return HttpResponseHelper.json(response);
    }
}
