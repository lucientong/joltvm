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
import com.joltvm.server.decompile.DecompileService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for {@code GET /api/classes/{className}/source} — decompiles a loaded class
 * and returns its Java source code.
 *
 * <p>Uses the CFR decompiler to convert bytecode back to readable Java source.
 * The bytecode is obtained from the target JVM's loaded classes via the
 * Instrumentation API.
 *
 * <p>Response format:
 * <pre>
 * {
 *   "className": "com.example.MyClass",
 *   "source": "package com.example;\n\npublic class MyClass {\n  ...\n}",
 *   "decompiler": "CFR"
 * }
 * </pre>
 */
public final class ClassSourceHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(ClassSourceHandler.class.getName());

    private final DecompileService decompileService;

    public ClassSourceHandler() {
        this.decompileService = new DecompileService();
    }

    // Visible for testing
    ClassSourceHandler(DecompileService decompileService) {
        this.decompileService = decompileService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String className = pathParams.get("className");
        if (className == null || className.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Class name is required");
        }

        if (!InstrumentationHolder.isAvailable()) {
            return HttpResponseHelper.error(HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Instrumentation not available");
        }

        // Find the class
        Class<?> targetClass = ClassFinder.findClass(className);
        if (targetClass == null) {
            return HttpResponseHelper.notFound("Class not found: " + className);
        }

        // Decompile
        try {
            String source = decompileService.decompile(targetClass);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("className", className);
            result.put("source", source);
            result.put("decompiler", "CFR");

            return HttpResponseHelper.json(result);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to decompile class: " + className, e);
            return HttpResponseHelper.serverError("Failed to decompile the requested class.");
        }
    }

}
