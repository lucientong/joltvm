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
 * Handler for {@code GET /api/threads} — lists all live threads.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code state} — filter by thread state (e.g., RUNNABLE, BLOCKED, WAITING)</li>
 * </ul>
 */
public class ThreadListHandler implements RouteHandler {

    private final ThreadDiagnosticsService threadService;

    public ThreadListHandler(ThreadDiagnosticsService threadService) {
        this.threadService = threadService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());

        Thread.State stateFilter = null;
        List<String> stateParam = decoder.parameters().get("state");
        if (stateParam != null && !stateParam.isEmpty()) {
            try {
                stateFilter = Thread.State.valueOf(stateParam.get(0).toUpperCase());
            } catch (IllegalArgumentException e) {
                return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                        "Invalid thread state: " + stateParam.get(0)
                        + ". Valid values: RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, NEW, TERMINATED");
            }
        }

        List<Map<String, Object>> threads = threadService.getAllThreads(stateFilter);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("threads", threads);
        response.put("count", threads.size());
        response.put("counts", threadService.getThreadCounts());
        return HttpResponseHelper.json(response);
    }
}
