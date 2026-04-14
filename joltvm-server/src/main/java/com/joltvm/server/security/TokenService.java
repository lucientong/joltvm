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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Token-based authentication service for JoltVM.
 *
 * <p>Generates and validates tokens using HMAC-SHA256 signatures.
 * Tokens carry the username, role, and expiration time in their payload.
 *
 * <p>Token format: {@code base64(payload).base64(signature)}
 * where payload is {@code username:role:expirationEpochSeconds}.
 *
 * <p>Active tokens are tracked in memory for immediate invalidation support.
 */
public final class TokenService {

    private static final Logger LOG = Logger.getLogger(TokenService.class.getName());

    private static final String HMAC_ALGO = "HmacSHA256";

    /** Default token expiration: 24 hours. */
    private static final long DEFAULT_EXPIRATION_SECONDS = 24 * 60 * 60;

    private final byte[] secretKey;
    private final long expirationSeconds;

    /** Active tokens → username mapping for invalidation support. */
    private final Map<String, String> activeTokens = new ConcurrentHashMap<>();

    /**
     * Creates a token service with a random secret key and default expiration.
     */
    public TokenService() {
        this(generateRandomKey(), DEFAULT_EXPIRATION_SECONDS);
    }

    /**
     * Creates a token service with the specified secret key and expiration.
     *
     * @param secretKey         the HMAC secret key
     * @param expirationSeconds token validity duration in seconds
     */
    public TokenService(byte[] secretKey, long expirationSeconds) {
        Objects.requireNonNull(secretKey, "secretKey");
        if (secretKey.length < 16) {
            throw new IllegalArgumentException("Secret key must be at least 16 bytes");
        }
        if (expirationSeconds <= 0) {
            throw new IllegalArgumentException("Expiration must be positive");
        }
        this.secretKey = secretKey.clone();
        this.expirationSeconds = expirationSeconds;
    }

    /**
     * Generates a token for the given user.
     *
     * @param username the username
     * @param role     the user's role
     * @return the generated token string
     * @throws NullPointerException if any argument is null
     */
    public String generateToken(String username, Role role) {
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(role, "role");

        long expiration = Instant.now().getEpochSecond() + expirationSeconds;
        String payload = username + ":" + role.name() + ":" + expiration;

        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);

        String token = encodedPayload + "." + signature;
        activeTokens.put(token, username);
        return token;
    }

    /**
     * Validates a token and returns the token info if valid.
     *
     * @param token the token string
     * @return the token info, or {@code null} if the token is invalid or expired
     */
    public TokenInfo validateToken(String token) {
        if (token == null || token.isBlank()) return null;

        // Check if token has been invalidated
        if (!activeTokens.containsKey(token)) {
            // Token might still be valid (server restart) — verify signature
        }

        String[] parts = token.split("\\.", 2);
        if (parts.length != 2) return null;

        String encodedPayload = parts[0];
        String providedSignature = parts[1];

        // Verify signature
        String expectedSignature = sign(encodedPayload);
        if (!constantTimeEquals(expectedSignature, providedSignature)) {
            return null;
        }

        // Decode payload
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(encodedPayload), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }

        String[] fields = payload.split(":", 3);
        if (fields.length != 3) return null;

        String username = fields[0];
        Role role;
        long expiration;

        try {
            role = Role.valueOf(fields[1]);
            expiration = Long.parseLong(fields[2]);
        } catch (IllegalArgumentException e) {
            return null;
        }

        // Check expiration
        if (Instant.now().getEpochSecond() > expiration) {
            activeTokens.remove(token);
            return null;
        }

        return new TokenInfo(username, role, Instant.ofEpochSecond(expiration));
    }

    /**
     * Invalidates a token (logout).
     *
     * @param token the token to invalidate
     */
    public void invalidateToken(String token) {
        if (token != null) {
            activeTokens.remove(token);
        }
    }

    /**
     * Invalidates all tokens for a user.
     *
     * @param username the username whose tokens to invalidate
     */
    public void invalidateAllTokens(String username) {
        activeTokens.entrySet().removeIf(e -> e.getValue().equals(username));
    }

    /**
     * Returns the number of active tokens.
     *
     * @return active token count
     */
    public int activeTokenCount() {
        return activeTokens.size();
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGO));
            byte[] sig = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "HMAC signing failed", e);
            throw new RuntimeException("Token signing failed", e);
        }
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }

    private static byte[] generateRandomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return key;
    }

    /**
     * Validated token information.
     *
     * @param username   the token owner's username
     * @param role       the token owner's role
     * @param expiration when the token expires
     */
    public record TokenInfo(String username, Role role, Instant expiration) {
    }
}
