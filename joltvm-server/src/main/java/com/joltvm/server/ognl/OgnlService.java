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

package com.joltvm.server.ognl;

import ognl.Ognl;
import ognl.OgnlContext;
import ognl.OgnlException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for safe OGNL expression evaluation against JVM runtime objects.
 *
 * <p>Security model (defense-in-depth):
 * <ol>
 *   <li><b>Pre-parse validation</b> — {@link SafeOgnlMemberAccess#validateExpression(String)}
 *       rejects expressions containing known dangerous patterns before parsing</li>
 *   <li><b>MemberAccess sandbox</b> — {@link SafeOgnlMemberAccess} blocks access to
 *       dangerous classes, methods, packages, and reflection</li>
 *   <li><b>Execution timeout</b> — 5-second hard limit via dedicated thread pool</li>
 *   <li><b>Result depth limit</b> — prevents circular reference infinite recursion</li>
 * </ol>
 */
public class OgnlService {

    private static final Logger LOG = Logger.getLogger(OgnlService.class.getName());

    /** Maximum execution time for a single expression (milliseconds). */
    private static final long TIMEOUT_MS = 5000;

    /** Thread pool for sandboxed expression evaluation. */
    private final ExecutorService executor;

    /** Security sandbox for member access control. */
    private final SafeOgnlMemberAccess memberAccess;

    public OgnlService() {
        this.memberAccess = new SafeOgnlMemberAccess();
        // Single-threaded pool — only one expression at a time
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "joltvm-ognl-eval");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Evaluates an OGNL expression safely.
     *
     * @param expression  the OGNL expression to evaluate
     * @param resultDepth maximum depth for result serialization (default 5)
     * @return evaluation result map with success/error, result, type, execTimeMs
     */
    public Map<String, Object> evaluate(String expression, int resultDepth) {
        long startTime = System.nanoTime();
        Map<String, Object> response = new LinkedHashMap<>();

        try {
            // Layer 1: Pre-parse expression validation
            SafeOgnlMemberAccess.validateExpression(expression);

            // Layer 2: Parse + evaluate with timeout and MemberAccess sandbox
            Object result = evaluateWithTimeout(expression);

            long execTimeMs = (System.nanoTime() - startTime) / 1_000_000;

            // Layer 3: Safe result serialization with depth limit
            int depth = resultDepth > 0 ? Math.min(resultDepth, 10) : ResultSerializer.DEFAULT_MAX_DEPTH;
            Object serialized = ResultSerializer.serialize(result, depth);

            response.put("success", true);
            response.put("result", serialized);
            response.put("type", result != null ? result.getClass().getName() : "null");
            response.put("execTimeMs", execTimeMs);
            return response;

        } catch (SecurityException e) {
            long execTimeMs = (System.nanoTime() - startTime) / 1_000_000;
            response.put("success", false);
            response.put("error", e.getMessage());
            response.put("errorType", "SECURITY");
            response.put("execTimeMs", execTimeMs);
            return response;

        } catch (TimeoutException e) {
            long execTimeMs = (System.nanoTime() - startTime) / 1_000_000;
            response.put("success", false);
            response.put("error", "Expression execution timed out after " + TIMEOUT_MS + "ms");
            response.put("errorType", "TIMEOUT");
            response.put("execTimeMs", execTimeMs);
            return response;

        } catch (OgnlException e) {
            long execTimeMs = (System.nanoTime() - startTime) / 1_000_000;
            String message = e.getMessage();
            // Check if the OGNL error is actually a security block
            if (message != null && (message.contains("access") || message.contains("not accessible"))) {
                response.put("success", false);
                response.put("error", "Access denied: " + message);
                response.put("errorType", "SECURITY");
            } else {
                response.put("success", false);
                response.put("error", "Expression error: " + message);
                response.put("errorType", "EVAL_ERROR");
            }
            response.put("execTimeMs", execTimeMs);
            return response;

        } catch (Exception e) {
            long execTimeMs = (System.nanoTime() - startTime) / 1_000_000;
            LOG.log(Level.WARNING, "Unexpected OGNL evaluation error", e);
            response.put("success", false);
            response.put("error", "Unexpected error: " + e.getClass().getSimpleName());
            response.put("errorType", "INTERNAL");
            response.put("execTimeMs", execTimeMs);
            return response;
        }
    }

    /**
     * Evaluates the expression in a sandboxed thread with timeout.
     */
    private Object evaluateWithTimeout(String expression)
            throws OgnlException, TimeoutException, SecurityException {

        Callable<Object> task = () -> {
            OgnlContext context = Ognl.createDefaultContext(null, memberAccess);
            // Set up useful context variables
            context.put("runtime", new RuntimeInfo());
            Object parsed = Ognl.parseExpression(expression);
            return Ognl.getValue(parsed, context, context);
        };

        Future<Object> future = executor.submit(task);
        try {
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OgnlException oe) throw oe;
            if (cause instanceof SecurityException se) throw se;
            throw new RuntimeException("Expression evaluation failed", cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Expression evaluation interrupted", e);
        }
    }

    /**
     * Shuts down the evaluation thread pool.
     */
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * Read-only runtime info object exposed as `#runtime` in OGNL context.
     * Provides safe access to JVM info without exposing dangerous APIs.
     */
    public static class RuntimeInfo {
        public long freeMemory() { return Runtime.getRuntime().freeMemory(); }
        public long totalMemory() { return Runtime.getRuntime().totalMemory(); }
        public long maxMemory() { return Runtime.getRuntime().maxMemory(); }
        public int availableProcessors() { return Runtime.getRuntime().availableProcessors(); }
        public long currentTimeMillis() { return System.currentTimeMillis(); }
        public long nanoTime() { return System.nanoTime(); }
        public String javaVersion() { return System.getProperty("java.version"); }
        public String osName() { return System.getProperty("os.name"); }
    }
}
