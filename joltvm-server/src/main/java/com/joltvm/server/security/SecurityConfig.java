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

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security configuration for JoltVM.
 *
 * <p>Manages authentication settings and user credentials.
 * When security is disabled, all requests are allowed without authentication.
 *
 * <p>Default admin credentials: admin / joltvm (should be changed in production).
 */
public final class SecurityConfig {

    /** Default admin username. */
    public static final String DEFAULT_ADMIN_USER = "admin";

    /** Default admin password. */
    public static final String DEFAULT_ADMIN_PASSWORD = "joltvm";

    private volatile boolean enabled;
    private final ConcurrentHashMap<String, UserEntry> users = new ConcurrentHashMap<>();

    /**
     * Creates a security config with authentication disabled.
     */
    public SecurityConfig() {
        this(false);
    }

    /**
     * Creates a security config.
     *
     * @param enabled whether authentication is enabled
     */
    public SecurityConfig(boolean enabled) {
        this.enabled = enabled;
        // Always register the default admin user
        users.put(DEFAULT_ADMIN_USER, new UserEntry(DEFAULT_ADMIN_USER, DEFAULT_ADMIN_PASSWORD, Role.ADMIN));
    }

    /**
     * Returns whether authentication is enabled.
     *
     * @return {@code true} if authentication is required
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Enables or disables authentication.
     *
     * @param enabled whether to enable authentication
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Adds or updates a user.
     *
     * @param username the username
     * @param password the password
     * @param role     the user's role
     * @throws NullPointerException if any argument is null
     */
    public void addUser(String username, String password, Role role) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(role, "role");
        users.put(username, new UserEntry(username, password, role));
    }

    /**
     * Removes a user.
     *
     * @param username the username to remove
     * @return {@code true} if the user was removed
     */
    public boolean removeUser(String username) {
        return users.remove(username) != null;
    }

    /**
     * Validates credentials and returns the user entry if valid.
     *
     * @param username the username
     * @param password the password
     * @return the user entry, or {@code null} if credentials are invalid
     */
    public UserEntry authenticate(String username, String password) {
        if (username == null || password == null) return null;
        UserEntry entry = users.get(username);
        if (entry != null && entry.password().equals(password)) {
            return entry;
        }
        return null;
    }

    /**
     * Returns the user entry for a username.
     *
     * @param username the username
     * @return the user entry, or {@code null} if not found
     */
    public UserEntry getUser(String username) {
        return users.get(username);
    }

    /**
     * Returns an unmodifiable view of all users.
     *
     * @return map of username → user entry
     */
    public Map<String, UserEntry> getUsers() {
        return Collections.unmodifiableMap(users);
    }

    /**
     * Immutable record representing a registered user.
     *
     * @param username the username
     * @param password the password (stored in plain text for simplicity in embedded tool context)
     * @param role     the user's role
     */
    public record UserEntry(String username, String password, Role role) {
    }
}
