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
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * Security configuration for JoltVM.
 *
 * <p>Manages authentication settings and user credentials.
 * Passwords are stored as salted SHA-256 hashes — never in plain text.
 * When security is disabled, all requests are allowed without authentication.
 *
 * <p>Password hash format (PBKDF2): {@code $pbkdf2-sha256$iterations$base64(salt)$base64(hash)}
 * <p>Legacy format (SHA-256, read-only for migration): {@code hex(salt)$hex(SHA-256(salt || password))}
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
            // Migrate legacy SHA-256 hash to PBKDF2 on successful login
            if (isLegacyHash(entry.passwordHash())) {
                String upgraded = encodePassword(password);
                UserEntry migrated = new UserEntry(
                        entry.username(), upgraded, entry.role(), entry.passwordChangeRequired());
                users.put(username, migrated);
                return migrated;
            }
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

    // ── Password hashing utilities (PBKDF2) ────────────────────────────────────

    /** PBKDF2 iteration count — OWASP 2024 recommendation for PBKDF2-SHA256. */
    static final int PBKDF2_ITERATIONS = 310_000;

    /** Derived key length in bits. */
    private static final int KEY_LENGTH_BITS = 256;

    /** Salt length in bytes. */
    private static final int SALT_LENGTH_BYTES = 16;

    /** Prefix identifying PBKDF2 hashes vs legacy SHA-256 hashes. */
    private static final String PBKDF2_PREFIX = "$pbkdf2-sha256$";

    /**
     * Encodes a plaintext password as PBKDF2-SHA256 hash.
     * Format: {@code $pbkdf2-sha256$iterations$base64(salt)$base64(hash)}
     *
     * @param plaintext the plaintext password
     * @return the encoded hash string
     */
    static String encodePassword(String plaintext) {
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(plaintext, salt, PBKDF2_ITERATIONS);
        return PBKDF2_PREFIX + PBKDF2_ITERATIONS + "$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    /**
     * Verifies a plaintext password against an encoded hash.
     * Supports both PBKDF2 (new) and legacy SHA-256 formats.
     *
     * @param plaintext the plaintext password to verify
     * @param encoded   the stored encoded hash
     * @return {@code true} if the password matches
     */
    static boolean verifyPassword(String plaintext, String encoded) {
        if (encoded == null) return false;
        if (encoded.startsWith(PBKDF2_PREFIX)) {
            return verifyPbkdf2(plaintext, encoded);
        }
        // Legacy SHA-256 format: hex(salt)$hex(hash)
        return verifyLegacySha256(plaintext, encoded);
    }

    /**
     * Returns whether the encoded hash uses the legacy SHA-256 format
     * and should be re-hashed with PBKDF2 on next successful login.
     */
    static boolean isLegacyHash(String encoded) {
        return encoded != null && !encoded.startsWith(PBKDF2_PREFIX);
    }

    private static boolean verifyPbkdf2(String plaintext, String encoded) {
        // Format: $pbkdf2-sha256$iterations$base64(salt)$base64(hash)
        String withoutPrefix = encoded.substring(PBKDF2_PREFIX.length());
        String[] parts = withoutPrefix.split("\\$", 3);
        if (parts.length != 3) return false;

        try {
            int iterations = Integer.parseInt(parts[0]);
            byte[] salt = Base64.getDecoder().decode(parts[1]);
            byte[] expectedHash = Base64.getDecoder().decode(parts[2]);
            byte[] actualHash = pbkdf2(plaintext, salt, iterations);
            return constantEquals(actualHash, expectedHash);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean verifyLegacySha256(String plaintext, String encoded) {
        String[] parts = encoded.split("\\$", 2);
        if (parts.length != 2) return false;
        String expectedHash = legacySha256Hash(plaintext, parts[0]);
        return constantEquals(expectedHash, parts[1]);
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(
                    password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2WithHmacSHA256 not available", e);
        }
    }

    /**
     * Legacy SHA-256 hash for backward compatibility during migration.
     */
    private static String legacySha256Hash(String password, String salt) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            md.update(salt.getBytes(StandardCharsets.UTF_8));
            byte[] hash = md.digest(password.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * Constant-time comparison for byte arrays to prevent timing attacks.
     */
    private static boolean constantEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * Constant-time comparison for strings to prevent timing attacks.
     */
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
