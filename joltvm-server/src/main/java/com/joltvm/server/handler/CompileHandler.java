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
import com.joltvm.server.compile.CompileResult;
import com.joltvm.server.compile.InMemoryCompiler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for {@code POST /api/compile} — compiles Java source code in memory.
 *
 * <p>Expects a JSON request body:
 * <pre>
 * {
 *   "className": "com.example.MyClass",
 *   "sourceCode": "package com.example;\n\npublic class MyClass { ... }"
 * }
 * </pre>
 *
 * <p>Returns the compilation result:
 * <pre>
 * {
 *   "success": true,
 *   "className": "com.example.MyClass",
 *   "classCount": 1,
 *   "message": "Compilation successful"
 * }
 * </pre>
 *
 * <p>On failure:
 * <pre>
 * {
 *   "success": false,
 *   "className": "com.example.MyClass",
 *   "diagnostics": ["Line 5: ';' expected"]
 * }
 * </pre>
 */
public class CompileHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(CompileHandler.class.getName());

    private final InMemoryCompiler compiler;

    public CompileHandler() {
        this.compiler = new InMemoryCompiler();
    }

    // Visible for testing
    CompileHandler(InMemoryCompiler compiler) {
        this.compiler = compiler;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        // Parse JSON body
        String body = request.content().toString(StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Request body is required. Expected JSON with 'className' and 'sourceCode' fields.");
        }

        Map<?, ?> bodyMap;
        try {
            bodyMap = HttpResponseHelper.gson().fromJson(body, Map.class);
        } catch (Exception e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Invalid JSON in request body.");
        }

        String className = (String) bodyMap.get("className");
        String sourceCode = (String) bodyMap.get("sourceCode");

        if (className == null || className.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'className' is required");
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'sourceCode' is required");
        }

        try {
            CompileResult result = compiler.compile(className, sourceCode);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", result.success());
            response.put("className", className);

            if (result.success()) {
                response.put("classCount", result.bytecodeMap().size());
                response.put("message", "Compilation successful");
                return HttpResponseHelper.json(response);
            } else {
                response.put("diagnostics", result.diagnostics());
                return HttpResponseHelper.json(HttpResponseStatus.UNPROCESSABLE_ENTITY, response);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Compilation error for " + className, e);
            return HttpResponseHelper.error(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Compilation error: " + e.getMessage());
        }
    }
}
