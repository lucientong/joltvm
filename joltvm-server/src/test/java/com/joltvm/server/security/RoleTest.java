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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Role}.
 */
@DisplayName("Role")
class RoleTest {

    @Test
    @DisplayName("three roles exist with correct levels")
    void threeRolesExist() {
        assertEquals(3, Role.values().length);
        assertEquals(1, Role.VIEWER.level());
        assertEquals(2, Role.OPERATOR.level());
        assertEquals(3, Role.ADMIN.level());
    }

    @Test
    @DisplayName("ADMIN has permission for all roles")
    void adminHasAllPermissions() {
        assertTrue(Role.ADMIN.hasPermission(Role.VIEWER));
        assertTrue(Role.ADMIN.hasPermission(Role.OPERATOR));
        assertTrue(Role.ADMIN.hasPermission(Role.ADMIN));
    }

    @Test
    @DisplayName("OPERATOR has VIEWER permission but not ADMIN")
    void operatorPermissions() {
        assertTrue(Role.OPERATOR.hasPermission(Role.VIEWER));
        assertTrue(Role.OPERATOR.hasPermission(Role.OPERATOR));
        assertFalse(Role.OPERATOR.hasPermission(Role.ADMIN));
    }

    @Test
    @DisplayName("VIEWER can only access VIEWER level")
    void viewerPermissions() {
        assertTrue(Role.VIEWER.hasPermission(Role.VIEWER));
        assertFalse(Role.VIEWER.hasPermission(Role.OPERATOR));
        assertFalse(Role.VIEWER.hasPermission(Role.ADMIN));
    }
}
