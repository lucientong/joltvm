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

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.joltvm.server.HttpResponseHelper;
import com.joltvm.server.RouteHandler;
import com.joltvm.server.profiler.AsyncProfilerService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handler for {@code POST /api/profiler/async/start} — starts async-profiler.
 *
 * <p>Request body:
 * <pre>{"event": "cpu", "duration": 30, "interval": 10000000}</pre>
 */
public class AsyncProfilerStartHandler implements RouteHandler {

    private static final Gson GSON = new Gson();
    private final AsyncProfilerService service;

    public AsyncProfilerStartHandler(AsyncProfilerService service) {
        this.service = service;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        JsonObject json;
        try {
            json = GSON.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Invalid JSON in request body.");
        }

        String event = json != null && json.has("event") ? json.get("event").getAsString() : "cpu";
        int duration = json != null && json.has("duration") ? json.get("duration").getAsInt() : 30;
        long interval = json != null && json.has("interval") ? json.get("interval").getAsLong() : 0;

        try {
            return HttpResponseHelper.json(service.start(event, duration, interval));
        } catch (IllegalStateException | IllegalArgumentException e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
