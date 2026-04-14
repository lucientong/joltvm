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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SecurityConfig}.
 */
@DisplayName("SecurityConfig")
class SecurityConfigTest {

    private SecurityConfig config;

    @BeforeEach
    void setUp() {
        config = new SecurityConfig(true);
    }

    @Nested
    @DisplayName("defaults")
    class Defaults {

        @Test
        @DisplayName("security is enabled when created with true")
        void enabledByDefault() {
            assertTrue(config.isEnabled());
        }

        @Test
        @DisplayName("default constructor creates disabled config")
        void disabledByDefault() {
            SecurityConfig disabled = new SecurityConfig();
            assertFalse(disabled.isEnabled());
        }

        @Test
        @DisplayName("default admin user exists with hashed password")
        void defaultAdminExists() {
            SecurityConfig.UserEntry admin = config.getUser("admin");
            assertNotNull(admin);
            assertEquals("admin", admin.username());
            // Password is stored as a salted hash, never plain text
            assertNotEquals("joltvm", admin.passwordHash());
            assertTrue(admin.passwordHash().contains("$"), "Hash should be in salt$hash format");
            assertEquals(Role.ADMIN, admin.role());
        }

        @Test
        @DisplayName("default admin has passwordChangeRequired = true")
        void defaultAdminPasswordChangeRequired() {
            SecurityConfig.UserEntry admin = config.getUser("admin");
            assertNotNull(admin);
            assertTrue(admin.passwordChangeRequired(),
                    "Default password 'joltvm' should require a change");
        }
    }

    @Nested
    @DisplayName("authentication")
    class Authentication {

        @Test
        @DisplayName("authenticate with valid credentials returns user")
        void authenticateSuccess() {
            SecurityConfig.UserEntry user = config.authenticate("admin", "joltvm");
            assertNotNull(user);
            assertEquals("admin", user.username());
            assertEquals(Role.ADMIN, user.role());
        }

        @Test
        @DisplayName("authenticate with wrong password returns null")
        void authenticateWrongPassword() {
            assertNull(config.authenticate("admin", "wrong"));
        }

        @Test
        @DisplayName("authenticate with unknown user returns null")
        void authenticateUnknownUser() {
            assertNull(config.authenticate("unknown", "test"));
        }

        @Test
        @DisplayName("authenticate with null returns null")
        void authenticateNull() {
            assertNull(config.authenticate(null, null));
            assertNull(config.authenticate("admin", null));
            assertNull(config.authenticate(null, "joltvm"));
        }
    }

    @Nested
    @DisplayName("user management")
    class UserManagement {

        @Test
        @DisplayName("addUser and authenticate")
        void addAndAuthenticate() {
            config.addUser("viewer", "pass123", Role.VIEWER);
            SecurityConfig.UserEntry user = config.authenticate("viewer", "pass123");
            assertNotNull(user);
            assertEquals(Role.VIEWER, user.role());
        }

        @Test
        @DisplayName("removeUser prevents authentication")
        void removeUser() {
            config.addUser("temp", "pass", Role.OPERATOR);
            assertTrue(config.removeUser("temp"));
            assertNull(config.authenticate("temp", "pass"));
        }

        @Test
        @DisplayName("removeUser returns false for non-existent user")
        void removeNonExistent() {
            assertFalse(config.removeUser("nonexistent"));
        }

        @Test
        @DisplayName("getUsers returns all users")
        void getUsers() {
            config.addUser("op1", "pass", Role.OPERATOR);
            assertTrue(config.getUsers().containsKey("admin"));
            assertTrue(config.getUsers().containsKey("op1"));
        }

        @Test
        @DisplayName("addUser with null throws")
        void addUserNullThrows() {
            assertThrows(NullPointerException.class, () -> config.addUser(null, "pass", Role.VIEWER));
            assertThrows(NullPointerException.class, () -> config.addUser("user", null, Role.VIEWER));
            assertThrows(NullPointerException.class, () -> config.addUser("user", "pass", null));
        }
    }

    @Test
    @DisplayName("setEnabled toggles state")
    void setEnabled() {
        config.setEnabled(false);
        assertFalse(config.isEnabled());
        config.setEnabled(true);
        assertTrue(config.isEnabled());
    }
}
