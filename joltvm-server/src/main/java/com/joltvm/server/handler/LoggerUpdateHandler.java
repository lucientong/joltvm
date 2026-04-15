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
import com.joltvm.server.logger.LoggerService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handler for {@code PUT /api/loggers/{name}} — dynamically changes a logger's level.
 *
 * <p>Request body:
 * <pre>{"level": "DEBUG"}</pre>
 *
 * <p>Response:
 * <pre>{"framework": "Logback", "loggerName": "com.example", "previousLevel": "INFO", "newLevel": "DEBUG"}</pre>
 */
public class LoggerUpdateHandler implements RouteHandler {

    private static final Gson GSON = new Gson();

    private final LoggerService loggerService;

    public LoggerUpdateHandler(LoggerService loggerService) {
        this.loggerService = loggerService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String loggerName = pathParams.get("name");
        if (loggerName == null || loggerName.isEmpty()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Missing logger name");
        }

        String body = request.content().toString(StandardCharsets.UTF_8);
        JsonObject json;
        try {
            json = GSON.fromJson(body, JsonObject.class);
        } catch (Exception e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Invalid JSON in request body.");
        }

        if (json == null || !json.has("level")) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Request body must include 'level' field (e.g., {\"level\": \"DEBUG\"})");
        }

        String level = json.get("level").getAsString();
        if (level == null || level.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Level must not be empty");
        }

        try {
            Map<String, Object> result = loggerService.setLevel(loggerName, level);
            return HttpResponseHelper.json(result);
        } catch (IllegalArgumentException e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
