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

package com.joltvm.server.security;

import java.util.Map;

/**
 * Maps API endpoints to their required minimum {@link Role}.
 *
 * <p>Used by the authentication middleware in {@code HttpDispatcherHandler}
 * to enforce role-based access control on each request.
 *
 * <p>Endpoints not listed here default to {@link Role#VIEWER}.
 *
 * <h3>Permission Matrix</h3>
 * <table>
 *   <tr><th>Endpoint</th><th>Method</th><th>Required Role</th></tr>
 *   <tr><td>/api/health</td><td>GET</td><td>VIEWER</td></tr>
 *   <tr><td>/api/classes</td><td>GET</td><td>VIEWER</td></tr>
 *   <tr><td>/api/trace/records</td><td>GET</td><td>VIEWER</td></tr>
 *   <tr><td>/api/spring/*</td><td>GET</td><td>VIEWER</td></tr>
 *   <tr><td>/api/hotswap/history</td><td>GET</td><td>VIEWER</td></tr>
 *   <tr><td>/api/compile</td><td>POST</td><td>OPERATOR</td></tr>
 *   <tr><td>/api/hotswap</td><td>POST</td><td>OPERATOR</td></tr>
 *   <tr><td>/api/rollback</td><td>POST</td><td>OPERATOR</td></tr>
 *   <tr><td>/api/trace/start</td><td>POST</td><td>OPERATOR</td></tr>
 *   <tr><td>/api/trace/stop</td><td>POST</td><td>OPERATOR</td></tr>
 *   <tr><td>/api/audit/export</td><td>GET</td><td>ADMIN</td></tr>
 *   <tr><td>/api/auth/users</td><td>*</td><td>ADMIN</td></tr>
 * </table>
 */
public final class RoutePermissions {

    private RoutePermissions() {
        // Utility class
    }

    /**
     * Map of API path prefixes to their required minimum role.
     * More specific paths are checked first via the {@link #getRequiredRole} method.
     */
    private static final Map<String, Role> PERMISSIONS = Map.ofEntries(
            // Auth endpoints — no auth required (handled separately)
            // Map.entry("/api/auth/login", null),

            // Admin-only endpoints
            Map.entry("POST:/api/audit/export", Role.ADMIN),
            Map.entry("GET:/api/audit/export", Role.ADMIN),
            Map.entry("POST:/api/auth/users", Role.ADMIN),
            Map.entry("DELETE:/api/auth/users", Role.ADMIN),

            // Operator endpoints (hot-fix + trace operations)
            Map.entry("POST:/api/compile", Role.OPERATOR),
            Map.entry("POST:/api/hotswap", Role.OPERATOR),
            Map.entry("POST:/api/rollback", Role.OPERATOR),
            Map.entry("POST:/api/trace/start", Role.OPERATOR),
            Map.entry("POST:/api/trace/stop", Role.OPERATOR),

            // Everything else (GET endpoints) → VIEWER
            Map.entry("GET:/api/health", Role.VIEWER),
            Map.entry("GET:/api/classes", Role.VIEWER),
            Map.entry("GET:/api/hotswap/history", Role.VIEWER),
            Map.entry("GET:/api/trace/records", Role.VIEWER),
            Map.entry("GET:/api/trace/flamegraph", Role.VIEWER),
            Map.entry("GET:/api/trace/status", Role.VIEWER),
            Map.entry("GET:/api/spring/beans", Role.VIEWER),
            Map.entry("GET:/api/spring/mappings", Role.VIEWER),
            Map.entry("GET:/api/spring/dependencies", Role.VIEWER),

            // Thread diagnostics endpoints — VIEWER (read-only)
            Map.entry("GET:/api/threads", Role.VIEWER),
            Map.entry("GET:/api/threads/top", Role.VIEWER),
            Map.entry("GET:/api/threads/deadlocks", Role.VIEWER),
            Map.entry("GET:/api/threads/dump", Role.VIEWER),

            // JVM info endpoints — VIEWER (read-only)
            Map.entry("GET:/api/jvm/gc", Role.VIEWER),
            Map.entry("GET:/api/jvm/sysprops", Role.VIEWER),
            Map.entry("GET:/api/jvm/sysenv", Role.VIEWER),
            Map.entry("GET:/api/jvm/classpath", Role.VIEWER),

            // ClassLoader analysis endpoints — VIEWER (read-only)
            Map.entry("GET:/api/classloaders", Role.VIEWER),
            Map.entry("GET:/api/classloaders/conflicts", Role.VIEWER),

            // Logger endpoints — GET is VIEWER, PUT is OPERATOR
            Map.entry("GET:/api/loggers", Role.VIEWER),
            Map.entry("PUT:/api/loggers", Role.OPERATOR),

            // OGNL expression engine — OPERATOR (executes arbitrary expressions)
            Map.entry("POST:/api/ognl/eval", Role.OPERATOR),

            // Watch command endpoints
            Map.entry("POST:/api/watch/start", Role.OPERATOR),
            Map.entry("GET:/api/watch", Role.VIEWER),
            Map.entry("POST:/api/watch", Role.OPERATOR),
            Map.entry("DELETE:/api/watch", Role.OPERATOR)
    );

    /**
     * Returns the required role for the given HTTP method and path.
     *
     * <p>Matching rules (in order):
     * <ol>
     *   <li>Exact match: {@code METHOD:/exact/path}</li>
     *   <li>Login/auth endpoints: no auth required (returns {@code null})</li>
     *   <li>Static files (no /api/ prefix): no auth required (returns {@code null})</li>
     *   <li>Default: {@link Role#VIEWER}</li>
     * </ol>
     *
     * @param method the HTTP method (GET, POST, etc.)
     * @param path   the request path
     * @return the required role, or {@code null} if no auth is needed
     */
    public static Role getRequiredRole(String method, String path) {
        // Login endpoints — no auth required
        if (path.equals("/api/auth/login") || path.equals("/api/auth/status")) {
            return null;
        }

        // Static files and root — no auth required
        if (!path.startsWith("/api/")) {
            return null;
        }

        // Exact match
        String key = method + ":" + path;
        Role exactMatch = PERMISSIONS.get(key);
        if (exactMatch != null) {
            return exactMatch;
        }

        // Prefix match for parameterized routes (e.g., /api/classes/{className})
        for (Map.Entry<String, Role> entry : PERMISSIONS.entrySet()) {
            String entryKey = entry.getKey();
            if (entryKey.startsWith(method + ":")) {
                String entryPath = entryKey.substring(method.length() + 1);
                if (path.startsWith(entryPath + "/") || path.equals(entryPath)) {
                    return entry.getValue();
                }
            }
        }

        // Default: any API endpoint requires at least VIEWER
        return Role.VIEWER;
    }
}
