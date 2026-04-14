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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RoutePermissions}.
 */
@DisplayName("RoutePermissions")
class RoutePermissionsTest {

    @Nested
    @DisplayName("no auth required")
    class NoAuth {

        @Test
        @DisplayName("login endpoint requires no auth")
        void loginNoAuth() {
            assertNull(RoutePermissions.getRequiredRole("POST", "/api/auth/login"));
        }

        @Test
        @DisplayName("auth status requires no auth")
        void authStatusNoAuth() {
            assertNull(RoutePermissions.getRequiredRole("GET", "/api/auth/status"));
        }

        @Test
        @DisplayName("static files require no auth")
        void staticFilesNoAuth() {
            assertNull(RoutePermissions.getRequiredRole("GET", "/"));
            assertNull(RoutePermissions.getRequiredRole("GET", "/css/app.css"));
            assertNull(RoutePermissions.getRequiredRole("GET", "/js/app.js"));
        }
    }

    @Nested
    @DisplayName("VIEWER endpoints")
    class ViewerEndpoints {

        @Test
        @DisplayName("health requires VIEWER")
        void health() {
            assertEquals(Role.VIEWER, RoutePermissions.getRequiredRole("GET", "/api/health"));
        }

        @Test
        @DisplayName("class list requires VIEWER")
        void classes() {
            assertEquals(Role.VIEWER, RoutePermissions.getRequiredRole("GET", "/api/classes"));
        }

        @Test
        @DisplayName("class detail requires VIEWER (prefix match)")
        void classDetail() {
            assertEquals(Role.VIEWER, RoutePermissions.getRequiredRole("GET", "/api/classes/com.example.Test"));
        }

        @Test
        @DisplayName("spring beans requires VIEWER")
        void springBeans() {
            assertEquals(Role.VIEWER, RoutePermissions.getRequiredRole("GET", "/api/spring/beans"));
        }

        @Test
        @DisplayName("trace status requires VIEWER")
        void traceStatus() {
            assertEquals(Role.VIEWER, RoutePermissions.getRequiredRole("GET", "/api/trace/status"));
        }

        @Test
        @DisplayName("hotswap history requires VIEWER")
        void hotswapHistory() {
            assertEquals(Role.VIEWER, RoutePermissions.getRequiredRole("GET", "/api/hotswap/history"));
        }
    }

    @Nested
    @DisplayName("OPERATOR endpoints")
    class OperatorEndpoints {

        @Test
        @DisplayName("compile requires OPERATOR")
        void compile() {
            assertEquals(Role.OPERATOR, RoutePermissions.getRequiredRole("POST", "/api/compile"));
        }

        @Test
        @DisplayName("hotswap requires OPERATOR")
        void hotswap() {
            assertEquals(Role.OPERATOR, RoutePermissions.getRequiredRole("POST", "/api/hotswap"));
        }

        @Test
        @DisplayName("rollback requires OPERATOR")
        void rollback() {
            assertEquals(Role.OPERATOR, RoutePermissions.getRequiredRole("POST", "/api/rollback"));
        }

        @Test
        @DisplayName("trace start requires OPERATOR")
        void traceStart() {
            assertEquals(Role.OPERATOR, RoutePermissions.getRequiredRole("POST", "/api/trace/start"));
        }

        @Test
        @DisplayName("trace stop requires OPERATOR")
        void traceStop() {
            assertEquals(Role.OPERATOR, RoutePermissions.getRequiredRole("POST", "/api/trace/stop"));
        }
    }

    @Nested
    @DisplayName("ADMIN endpoints")
    class AdminEndpoints {

        @Test
        @DisplayName("audit export requires ADMIN")
        void auditExport() {
            assertEquals(Role.ADMIN, RoutePermissions.getRequiredRole("GET", "/api/audit/export"));
        }
    }

    @Test
    @DisplayName("unknown API endpoint defaults to VIEWER")
    void unknownEndpoint() {
        assertEquals(Role.VIEWER, RoutePermissions.getRequiredRole("GET", "/api/unknown"));
    }
}
