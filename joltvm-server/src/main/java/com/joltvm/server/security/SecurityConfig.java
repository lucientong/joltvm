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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Security configuration for JoltVM.
 *
 * <p>Manages authentication settings and user credentials.
 * Passwords are stored as salted SHA-256 hashes — never in plain text.
 * When security is disabled, all requests are allowed without authentication.
 *
 * <p>Password hash format: {@code hex(salt)$hex(SHA-256(salt || password))}
 */
public final class SecurityConfig {

    /** Default admin username. */
    public static final String DEFAULT_ADMIN_USER = "admin";

    /** Default admin password — users should change this on first login. */
    public static final String DEFAULT_ADMIN_PASSWORD = "joltvm";

    private volatile boolean enabled;
    private final ConcurrentHashMap<String, UserEntry> users = new ConcurrentHashMap<>();

    /**
     * Creates a security config with authentication disabled and the default admin password.
     */
    public SecurityConfig() {
        this(false);
    }

    /**
     * Creates a security config with the given enabled state and the default admin password.
     *
     * @param enabled whether authentication is enabled
     */
    public SecurityConfig(boolean enabled) {
        this(enabled, DEFAULT_ADMIN_PASSWORD);
    }

    /**
     * Creates a security config with a custom admin password.
     *
     * <p>If {@code adminPassword} equals {@link #DEFAULT_ADMIN_PASSWORD}, the admin account
     * is flagged with {@code passwordChangeRequired = true} to prompt the user to set a
     * stronger password on first login.
     *
     * @param enabled       whether authentication is enabled
     * @param adminPassword the initial admin password (stored as a salted hash)
     */
    public SecurityConfig(boolean enabled, String adminPassword) {
        this.enabled = enabled;
        Objects.requireNonNull(adminPassword, "adminPassword");
        boolean changeRequired = DEFAULT_ADMIN_PASSWORD.equals(adminPassword);
        String encoded = encodePassword(adminPassword);
        users.put(DEFAULT_ADMIN_USER,
                new UserEntry(DEFAULT_ADMIN_USER, encoded, Role.ADMIN, changeRequired));
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
     * Adds or updates a user. The password is stored as a salted hash.
     *
     * @param username the username
     * @param password the plaintext password (will be hashed before storage)
     * @param role     the user's role
     * @throws NullPointerException if any argument is null
     */
    public void addUser(String username, String password, Role role) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(role, "role");
        users.put(username, new UserEntry(username, encodePassword(password), role, false));
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
     * @param password the plaintext password to verify
     * @return the user entry, or {@code null} if credentials are invalid
     */
    public UserEntry authenticate(String username, String password) {
        if (username == null || password == null) return null;
        UserEntry entry = users.get(username);
        if (entry != null && verifyPassword(password, entry.passwordHash())) {
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

    // ── Password hashing utilities ────────────────────────────────────────────

    /**
     * Encodes a plaintext password as {@code hex(salt)$hex(SHA-256(salt || password))}.
     *
     * @param plaintext the plaintext password
     * @return the encoded hash string
     */
    static String encodePassword(String plaintext) {
        String salt = generateSalt();
        return salt + "$" + hashPassword(plaintext, salt);
    }

    /**
     * Verifies a plaintext password against an encoded hash.
     *
     * @param plaintext the plaintext password to verify
     * @param encoded   the stored encoded hash
     * @return {@code true} if the password matches
     */
    static boolean verifyPassword(String plaintext, String encoded) {
        if (encoded == null) return false;
        String[] parts = encoded.split("\\$", 2);
        if (parts.length != 2) return false;
        String expectedHash = hashPassword(plaintext, parts[0]);
        return constantEquals(expectedHash, parts[1]);
    }

    private static String generateSalt() {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return bytesToHex(salt);
    }

    private static String hashPassword(String password, String salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private static boolean constantEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ── UserEntry record ──────────────────────────────────────────────────────

    /**
     * Immutable record representing a registered user.
     *
     * @param username              the username
     * @param passwordHash          the salted SHA-256 password hash (never plaintext)
     * @param role                  the user's role
     * @param passwordChangeRequired whether the user must change their password on next login
     */
    public record UserEntry(
            String username,
            String passwordHash,
            Role role,
            boolean passwordChangeRequired
    ) {
        /** Convenience constructor without the changeRequired flag (defaults to false). */
        public UserEntry(String username, String passwordHash, Role role) {
            this(username, passwordHash, role, false);
        }
    }
}
