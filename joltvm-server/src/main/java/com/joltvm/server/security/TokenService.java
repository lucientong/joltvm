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
import java.util.concurrent.atomic.AtomicInteger;
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
     * Revoked tokens → expiration time.
     * Tokens added here are always rejected, even if the signature is valid.
     * Entries are cleaned up lazily once they pass their expiration time.
     */
    private final Map<String, Instant> revokedTokens = new ConcurrentHashMap<>();

    /** Cleanup counter — triggers revocation cleanup every 100 validation calls. */
    private final AtomicInteger validateCallCount = new AtomicInteger(0);

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
     * <p>Checks the revocation list first: any token passed to
     * {@link #invalidateToken(String)} or {@link #invalidateAllTokens(String)} is
     * permanently rejected, even if its HMAC signature is still valid.
     *
     * @param token the token string
     * @return the token info, or {@code null} if the token is invalid, expired, or revoked
     */
    public TokenInfo validateToken(String token) {
        if (token == null || token.isBlank()) return null;

        // Lazily clean up expired revocation entries
        if (validateCallCount.incrementAndGet() % 100 == 0) {
            cleanupExpiredRevocations();
        }

        // Hard revocation check — rejected regardless of signature validity
        if (revokedTokens.containsKey(token)) {
            return null;
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
     * Invalidates (revokes) a token immediately.
     *
     * <p>The token is added to the revocation list so that subsequent calls to
     * {@link #validateToken(String)} will return {@code null}, even if the
     * HMAC signature is still valid and the token has not yet expired.
     *
     * @param token the token to invalidate
     */
    public void invalidateToken(String token) {
        if (token != null) {
            activeTokens.remove(token);
            Instant expiry = parseExpiration(token);
            revokedTokens.put(token,
                    expiry != null ? expiry : Instant.now().plusSeconds(expirationSeconds));
        }
    }

    /**
     * Invalidates all tokens for a user.
     *
     * @param username the username whose tokens to invalidate
     */
    public void invalidateAllTokens(String username) {
        activeTokens.entrySet().removeIf(entry -> {
            if (entry.getValue().equals(username)) {
                Instant expiry = parseExpiration(entry.getKey());
                revokedTokens.put(entry.getKey(),
                        expiry != null ? expiry : Instant.now().plusSeconds(expirationSeconds));
                return true;
            }
            return false;
        });
    }

    /**
     * Parses the expiration instant from a token's payload without full validation.
     *
     * @param token the token string
     * @return the expiration instant, or {@code null} if the token cannot be parsed
     */
    private Instant parseExpiration(String token) {
        try {
            String[] parts = token.split("\\.", 2);
            if (parts.length != 2) return null;
            String payload = new String(
                    Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            String[] fields = payload.split(":", 3);
            if (fields.length != 3) return null;
            return Instant.ofEpochSecond(Long.parseLong(fields[2]));
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes expired entries from the revocation list. */
    private void cleanupExpiredRevocations() {
        Instant now = Instant.now();
        revokedTokens.entrySet().removeIf(e -> now.isAfter(e.getValue()));
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

    /**
     * Extracts the operator username from a Bearer token string.
     *
     * <p>Validates the token (HMAC + expiration) before extracting the username.
     * Returns {@code null} if the token is missing, invalid, expired, or revoked.
     *
     * @param bearerToken the raw Bearer token (without the "Bearer " prefix)
     * @return the username, or {@code null}
     */
    public String extractUsername(String bearerToken) {
        if (bearerToken == null || bearerToken.isBlank()) return null;
        TokenInfo info = validateToken(bearerToken);
        return info != null ? info.username() : null;
    }
}
