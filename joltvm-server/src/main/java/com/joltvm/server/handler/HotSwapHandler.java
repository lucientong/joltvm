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
        String reason = bodyMap.get("reason") instanceof String r ? r : null;

        // Extract operator from Authorization header (if token-based auth is used)
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

            // Step 3: Generate unified source diff (best-effort, non-blocking)
            String diff = generateDiff(className, sourceCode, hotSwapService);

            // Step 4: Hot-swap (pass pre-computed diff)
            HotSwapRecord record = hotSwapService.hotSwap(className, newBytecode, operator, reason, diff);

            // Record to audit log if available
            AuditLogService auditLogService = com.joltvm.server.APIRoutes.getAuditLogService();
            if (auditLogService != null) {
                auditLogService.record(record);
            }

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

    /**
     * Generates a unified diff between the original (decompiled) source and the new source.
     *
     * <p>Returns {@code null} on any error — diff is purely informational and must not
     * block or fail the hot-swap operation.
     */
    private static String generateDiff(String className, String newSource, HotSwapService hotSwapService) {
        try {
            Optional<byte[]> backup = hotSwapService.getBackupService().getBackup(className);
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

    /**
     * Extracts the operator username from the Authorization Bearer token.
     *
     * <p>Decodes the token payload (base64) to extract the username field.
     * Returns {@code null} if no valid token is present.
     */
    private static String extractOperator(FullHttpRequest request) {
        String authHeader = request.headers().get("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring(7);
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length < 1) return null;
            String payload = new String(
                    java.util.Base64.getUrlDecoder().decode(parts[0]),
                    java.nio.charset.StandardCharsets.UTF_8);
            // payload format: username:role:expiration
            String[] fields = payload.split(":", 3);
            return fields.length > 0 ? fields[0] : null;
        } catch (Exception e) {
            return null;
        }
    }
}
