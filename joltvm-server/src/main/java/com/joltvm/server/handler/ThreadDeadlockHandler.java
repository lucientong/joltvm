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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/threads/deadlocks} — detects deadlocked threads.
 *
 * <p>Returns an empty array if no deadlocks are found.
 */
public class ThreadDeadlockHandler implements RouteHandler {

    private final ThreadDiagnosticsService threadService;

    public ThreadDeadlockHandler(ThreadDiagnosticsService threadService) {
        this.threadService = threadService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        List<Map<String, Object>> deadlocks = threadService.detectDeadlocks();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("deadlocked", !deadlocks.isEmpty());
        response.put("count", deadlocks.size());
        response.put("threads", deadlocks);
        return HttpResponseHelper.json(response);
    }
}
