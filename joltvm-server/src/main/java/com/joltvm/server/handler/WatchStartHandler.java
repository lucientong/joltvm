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
import com.joltvm.server.watch.WatchService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handler for {@code POST /api/watch/start} — starts a new watch session.
 *
 * <p>Request body:
 * <pre>{"classPattern": "com.example.MyService", "methodPattern": "handleRequest",
 *  "conditionExpr": "#cost > 1000000000", "maxRecords": 100, "durationMs": 60000}</pre>
 */
public class WatchStartHandler implements RouteHandler {

    private static final Gson GSON = new Gson();
    private final WatchService watchService;

    public WatchStartHandler(WatchService watchService) {
        this.watchService = watchService;
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

        if (json == null || !json.has("classPattern")) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Request body must include 'classPattern' field");
        }

        String classPattern = json.get("classPattern").getAsString();
        String methodPattern = json.has("methodPattern") ? json.get("methodPattern").getAsString() : "*";
        String conditionExpr = json.has("conditionExpr") ? json.get("conditionExpr").getAsString() : null;
        int maxRecords = json.has("maxRecords") ? json.get("maxRecords").getAsInt() : 1000;
        long durationMs = json.has("durationMs") ? json.get("durationMs").getAsLong() : 60000;

        try {
            Map<String, Object> result = watchService.startWatch(classPattern, methodPattern,
                    conditionExpr, maxRecords, durationMs);
            return HttpResponseHelper.json(result);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
