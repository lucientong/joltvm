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
import com.joltvm.server.profiler.AsyncProfilerService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;

/**
 * Handler for {@code POST /api/profiler/async/stop} — stops async-profiler.
 */
public class AsyncProfilerStopHandler implements RouteHandler {

    private final AsyncProfilerService service;

    public AsyncProfilerStopHandler(AsyncProfilerService service) {
        this.service = service;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        try {
            return HttpResponseHelper.json(service.stop());
        } catch (IllegalStateException e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
