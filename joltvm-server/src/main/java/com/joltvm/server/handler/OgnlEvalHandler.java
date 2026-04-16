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
import com.joltvm.server.ognl.OgnlService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Handler for {@code POST /api/ognl/eval} — evaluates an OGNL expression
 * in a security sandbox.
 *
 * <p>Request body:
 * <pre>{"expression": "1+1", "resultDepth": 3}</pre>
 *
 * <p>Successful response:
 * <pre>{"success": true, "result": 2, "type": "java.lang.Integer", "execTimeMs": 5}</pre>
 *
 * <p>Security violation response:
 * <pre>{"success": false, "error": "Access denied: java.lang.Runtime", "errorType": "SECURITY"}</pre>
 */
public class OgnlEvalHandler implements RouteHandler {

    private static final Gson GSON = new Gson();
    private static final int MAX_EXPRESSION_LENGTH = 4096;

    private final OgnlService ognlService;

    public OgnlEvalHandler(OgnlService ognlService) {
        this.ognlService = ognlService;
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

        if (json == null || !json.has("expression")) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Request body must include 'expression' field");
        }

        String expression = json.get("expression").getAsString();
        if (expression == null || expression.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Expression must not be empty");
        }

        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Expression too long (max " + MAX_EXPRESSION_LENGTH + " characters)");
        }

        int resultDepth = 5;
        if (json.has("resultDepth")) {
            try {
                resultDepth = json.get("resultDepth").getAsInt();
            } catch (Exception ignored) {
                // use default
            }
        }

        Map<String, Object> result = ognlService.evaluate(expression, resultDepth);
        return HttpResponseHelper.json(result);
    }
}
