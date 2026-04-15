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

import java.util.Map;

/**
 * Handler for {@code GET /api/threads/{id}} — returns detailed info for a specific thread.
 */
public class ThreadDetailHandler implements RouteHandler {

    private final ThreadDiagnosticsService threadService;

    public ThreadDetailHandler(ThreadDiagnosticsService threadService) {
        this.threadService = threadService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String idStr = pathParams.get("id");
        if (idStr == null || idStr.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Thread ID is required");
        }

        long threadId;
        try {
            threadId = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Invalid thread ID: " + idStr);
        }

        Map<String, Object> detail = threadService.getThreadDetail(threadId);
        if (detail == null) {
            return HttpResponseHelper.error(HttpResponseStatus.NOT_FOUND,
                    "Thread not found: " + threadId);
        }

        return HttpResponseHelper.json(detail);
    }
}
