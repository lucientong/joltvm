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

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import com.joltvm.agent.InstrumentationHolder;
import com.joltvm.server.HttpResponseHelper;
import com.joltvm.server.RouteHandler;
import com.joltvm.server.classloader.AmbiguousClassException;
import com.joltvm.server.compile.CompileResult;
import com.joltvm.server.compile.InMemoryCompiler;
import com.joltvm.server.decompile.DecompileService;
import com.joltvm.server.hotswap.HotSwapRecord;
import com.joltvm.server.hotswap.HotSwapService;
import com.joltvm.server.security.AuditLogService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for {@code POST /api/hotswap} — compiles source code and applies hot-swap.
 *
 * <p>After compile, all classes in the bytecode map that are already loaded and
 * structurally redefinable are redefined in a single atomic
 * {@code Instrumentation.redefineClasses} call (outer + named inner classes).
 * Unloaded companions (e.g. anonymous {@code $1}) are listed under {@code skipped}.
 *
 * <p>Optional {@code classLoaderId} disambiguates when the same FQCN is loaded by
 * multiple ClassLoaders (HTTP 409 + candidates on conflict).
 *
 * <pre>
 * {
 *   "className": "com.example.MyClass",
 *   "sourceCode": "package com.example;\n\npublic class MyClass { ... }",
 *   "classLoaderId": "12345678"   // optional
 * }
 * </pre>
 */
public class HotSwapHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(HotSwapHandler.class.getName());

    private final InMemoryCompiler compiler;
    private final HotSwapService hotSwapService;
    private final com.joltvm.server.security.TokenService tokenService;

    public HotSwapHandler(HotSwapService hotSwapService) {
        this(new InMemoryCompiler(), hotSwapService, null);
    }

    public HotSwapHandler(HotSwapService hotSwapService,
                           com.joltvm.server.security.TokenService tokenService) {
        this(new InMemoryCompiler(), hotSwapService, tokenService);
    }

    // Visible for testing
    HotSwapHandler(InMemoryCompiler compiler, HotSwapService hotSwapService,
                   com.joltvm.server.security.TokenService tokenService) {
        this.compiler = compiler;
        this.hotSwapService = hotSwapService;
        this.tokenService = tokenService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!InstrumentationHolder.isAvailable()) {
            return HttpResponseHelper.error(HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Instrumentation not available");
        }

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
        String reason = bodyMap.get("reason") instanceof String r ? r : null;
        String classLoaderId = bodyMap.get("classLoaderId") instanceof String id ? id : null;
        if (classLoaderId != null && classLoaderId.isBlank()) {
            classLoaderId = null;
        }

        String operator = extractOperator(request);

        if (className == null || className.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'className' is required");
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'sourceCode' is required");
        }

        try {
            CompileResult compileResult = compiler.compile(className, sourceCode);
            if (!compileResult.success()) {
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("success", false);
                response.put("phase", "compile");
                response.put("className", className);
                response.put("diagnostics", compileResult.diagnostics());
                return HttpResponseHelper.json(HttpResponseStatus.UNPROCESSABLE_ENTITY, response);
            }

            Map<String, byte[]> bytecodeMap = compileResult.bytecodeMap();
            if (bytecodeMap.isEmpty()) {
                return HttpResponseHelper.error(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                        "Compilation succeeded but no bytecode was produced");
            }

            String diff = generateDiff(className, sourceCode, hotSwapService, classLoaderId);

            HotSwapService.BatchHotSwapResult batch = hotSwapService.hotSwapBatch(
                    bytecodeMap, className, classLoaderId, operator, reason, diff);

            HotSwapRecord record = batch.primaryRecord();
            AuditLogService auditLogService = com.joltvm.server.APIRoutes.getAuditLogService();
            if (auditLogService != null) {
                auditLogService.record(record);
            }

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", batch.success());
            response.put("phase", "hotswap");
            response.put("className", className);
            response.put("action", record.action().name());
            response.put("message", batch.message());
            response.put("recordId", record.id());
            response.put("timestamp", record.timestamp().toString());
            response.put("redefined", batch.redefined());
            response.put("skipped", batch.skipped());
            if (classLoaderId != null) {
                response.put("classLoaderId", classLoaderId);
            }

            if (batch.success()) {
                return HttpResponseHelper.json(response);
            } else {
                return HttpResponseHelper.json(HttpResponseStatus.INTERNAL_SERVER_ERROR, response);
            }

        } catch (AmbiguousClassException e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("className", e.getClassName());
            response.put("candidates", e.getCandidates());
            return HttpResponseHelper.json(HttpResponseStatus.CONFLICT, response);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Hot-swap error for " + className, e);
            return HttpResponseHelper.serverError("Hot-swap failed due to an internal error.");
        }
    }

    private static String generateDiff(String className, String newSource,
                                        HotSwapService hotSwapService, String classLoaderId) {
        try {
            Optional<byte[]> backup = classLoaderId != null
                    ? hotSwapService.getBackupService().getBackup(classLoaderId, className)
                    : Optional.empty();
            if (backup.isEmpty()) {
                backup = hotSwapService.getBackupService().getBackup(className);
            }
            if (backup.isEmpty()) {
                return null;
            }
            DecompileService decompiler = new DecompileService();
            String originalSource = decompiler.decompileFromBytecode(className, backup.get());

            List<String> original = Arrays.asList(originalSource.split("\n", -1));
            List<String> revised  = Arrays.asList(newSource.split("\n", -1));

            Patch<String> patch = DiffUtils.diff(original, revised);
            if (patch.getDeltas().isEmpty()) {
                return null;
            }

            String fileName = className.replace('.', '/') + ".java";
            List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(
                    "a/" + fileName, "b/" + fileName, original, patch, 3);
            return String.join("\n", unifiedDiff);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Diff generation skipped for " + className + ": " + e.getMessage());
            return null;
        }
    }

    private String extractOperator(FullHttpRequest request) {
        if (tokenService == null) {
            return null;
        }
        String authHeader = request.headers().get("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        return tokenService.extractUsername(authHeader.substring(7));
    }
}
