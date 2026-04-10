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

import com.joltvm.agent.InstrumentationHolder;
import com.joltvm.server.HttpResponseHelper;
import com.joltvm.server.RouteHandler;
import com.joltvm.server.compile.CompileResult;
import com.joltvm.server.compile.InMemoryCompiler;
import com.joltvm.server.hotswap.HotSwapRecord;
import com.joltvm.server.hotswap.HotSwapService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for {@code POST /api/hotswap} — compiles source code and applies hot-swap.
 *
 * <p>This handler performs the full hot-swap pipeline:
 * <ol>
 *   <li>Compile source code in memory</li>
 *   <li>Backup original bytecode</li>
 *   <li>Redefine the class with new bytecode</li>
 * </ol>
 *
 * <p>Expects a JSON request body:
 * <pre>
 * {
 *   "className": "com.example.MyClass",
 *   "sourceCode": "package com.example;\n\npublic class MyClass { ... }"
 * }
 * </pre>
 */
public class HotSwapHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(HotSwapHandler.class.getName());

    private final InMemoryCompiler compiler;
    private final HotSwapService hotSwapService;

    public HotSwapHandler(HotSwapService hotSwapService) {
        this.compiler = new InMemoryCompiler();
        this.hotSwapService = hotSwapService;
    }

    // Visible for testing
    HotSwapHandler(InMemoryCompiler compiler, HotSwapService hotSwapService) {
        this.compiler = compiler;
        this.hotSwapService = hotSwapService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!InstrumentationHolder.isAvailable()) {
            return HttpResponseHelper.error(HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Instrumentation not available");
        }

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
                    "Invalid JSON body: " + e.getMessage());
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
            // Step 1: Compile
            CompileResult compileResult = compiler.compile(className, sourceCode);
            if (!compileResult.success()) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", false);
                response.put("phase", "compile");
                response.put("className", className);
                response.put("diagnostics", compileResult.diagnostics());
                return HttpResponseHelper.json(HttpResponseStatus.UNPROCESSABLE_ENTITY, response);
            }

            // Step 2: Get bytecode for the target class
            byte[] newBytecode = compileResult.bytecodeMap().get(className);
            if (newBytecode == null) {
                // Try to find with simple class name (inner class might have different key)
                for (Map.Entry<String, byte[]> entry : compileResult.bytecodeMap().entrySet()) {
                    if (entry.getKey().endsWith(className.substring(className.lastIndexOf('.') + 1))) {
                        newBytecode = entry.getValue();
                        break;
                    }
                }
            }
            if (newBytecode == null) {
                return HttpResponseHelper.error(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Compilation succeeded but no bytecode found for " + className);
            }

            // Step 3: Hot-swap
            HotSwapRecord record = hotSwapService.hotSwap(className, newBytecode);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", record.status() == HotSwapRecord.Status.SUCCESS);
            response.put("phase", "hotswap");
            response.put("className", className);
            response.put("action", record.action().name());
            response.put("message", record.message());
            response.put("recordId", record.id());
            response.put("timestamp", record.timestamp().toString());

            if (record.status() == HotSwapRecord.Status.SUCCESS) {
                return HttpResponseHelper.json(response);
            } else {
                return HttpResponseHelper.json(HttpResponseStatus.INTERNAL_SERVER_ERROR, response);
            }

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Hot-swap error for " + className, e);
            return HttpResponseHelper.error(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Hot-swap error: " + e.getMessage());
        }
    }
}
