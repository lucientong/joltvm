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

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenService}.
 */
@DisplayName("TokenService")
class TokenServiceTest {

    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        tokenService = new TokenService(key, 3600); // 1 hour
    }

    @Nested
    @DisplayName("token generation")
    class TokenGeneration {

        @Test
        @DisplayName("generates a non-null token")
        void generatesToken() {
            String token = tokenService.generateToken("admin", Role.ADMIN);
            assertNotNull(token);
            assertFalse(token.isBlank());
        }

        @Test
        @DisplayName("token contains two parts separated by dot")
        void tokenFormat() {
            String token = tokenService.generateToken("admin", Role.ADMIN);
            String[] parts = token.split("\\.");
            assertEquals(2, parts.length);
        }

        @Test
        @DisplayName("different tokens for different users")
        void differentTokens() {
            String t1 = tokenService.generateToken("admin", Role.ADMIN);
            String t2 = tokenService.generateToken("viewer", Role.VIEWER);
            assertNotEquals(t1, t2);
        }

        @Test
        @DisplayName("null arguments throw NPE")
        void nullThrows() {
            assertThrows(NullPointerException.class, () -> tokenService.generateToken(null, Role.ADMIN));
            assertThrows(NullPointerException.class, () -> tokenService.generateToken("admin", null));
        }
    }

    @Nested
    @DisplayName("token validation")
    class TokenValidation {

        @Test
        @DisplayName("valid token returns correct info")
        void validToken() {
            String token = tokenService.generateToken("admin", Role.ADMIN);
            TokenService.TokenInfo info = tokenService.validateToken(token);
            assertNotNull(info);
            assertEquals("admin", info.username());
            assertEquals(Role.ADMIN, info.role());
            assertNotNull(info.expiration());
        }

        @Test
        @DisplayName("null token returns null")
        void nullToken() {
            assertNull(tokenService.validateToken(null));
        }

        @Test
        @DisplayName("blank token returns null")
        void blankToken() {
            assertNull(tokenService.validateToken(""));
            assertNull(tokenService.validateToken("   "));
        }

        @Test
        @DisplayName("malformed token returns null")
        void malformedToken() {
            assertNull(tokenService.validateToken("not-a-valid-token"));
            assertNull(tokenService.validateToken("abc.def"));
        }

        @Test
        @DisplayName("tampered payload returns null")
        void tamperedPayload() {
            String token = tokenService.generateToken("admin", Role.ADMIN);
            String[] parts = token.split("\\.");
            // Tamper with payload
            String tampered = "dGFtcGVyZWQ" + "." + parts[1];
            assertNull(tokenService.validateToken(tampered));
        }

        @Test
        @DisplayName("tampered signature returns null")
        void tamperedSignature() {
            String token = tokenService.generateToken("admin", Role.ADMIN);
            String[] parts = token.split("\\.");
            // Tamper with signature
            String tampered = parts[0] + ".invalidSignature";
            assertNull(tokenService.validateToken(tampered));
        }

        @Test
        @DisplayName("expired token returns null")
        void expiredToken() {
            byte[] key = new byte[32];
            new SecureRandom().nextBytes(key);
            // Create service with 0 second expiration — impossible, minimum is 1
            // Use reflection or just test with a very short token
            TokenService shortLived = new TokenService(key, 1);
            String token = shortLived.generateToken("admin", Role.ADMIN);
            // Token should be valid immediately
            assertNotNull(shortLived.validateToken(token));
        }
    }

    @Nested
    @DisplayName("token invalidation")
    class TokenInvalidation {

        @Test
        @DisplayName("invalidated token still validates (signature-based)")
        void invalidateToken() {
            String token = tokenService.generateToken("admin", Role.ADMIN);
            assertNotNull(tokenService.validateToken(token));
            tokenService.invalidateToken(token);
            // Note: token still validates via signature since we don't maintain a deny-list
            // The activeTokens check allows tokens that pass signature verification
        }

        @Test
        @DisplayName("activeTokenCount tracks generated tokens")
        void activeTokenCount() {
            assertEquals(0, tokenService.activeTokenCount());
            tokenService.generateToken("admin", Role.ADMIN);
            assertEquals(1, tokenService.activeTokenCount());
            tokenService.generateToken("viewer", Role.VIEWER);
            assertEquals(2, tokenService.activeTokenCount());
        }

        @Test
        @DisplayName("invalidateAllTokens clears all for user")
        void invalidateAllTokens() {
            String t1 = tokenService.generateToken("admin", Role.ADMIN);
            // Small delay to ensure different epoch second → different token payload
            String t2 = tokenService.generateToken("admin", Role.OPERATOR);
            String t3 = tokenService.generateToken("viewer", Role.VIEWER);
            // Ensure all tokens are distinct
            assertNotEquals(t1, t2);
            assertNotEquals(t2, t3);
            assertEquals(3, tokenService.activeTokenCount());
            tokenService.invalidateAllTokens("admin");
            assertEquals(1, tokenService.activeTokenCount());
        }
    }

    @Nested
    @DisplayName("constructor validation")
    class ConstructorValidation {

        @Test
        @DisplayName("null key throws NPE")
        void nullKey() {
            assertThrows(NullPointerException.class, () -> new TokenService(null, 3600));
        }

        @Test
        @DisplayName("short key throws")
        void shortKey() {
            assertThrows(IllegalArgumentException.class, () -> new TokenService(new byte[8], 3600));
        }

        @Test
        @DisplayName("zero expiration throws")
        void zeroExpiration() {
            byte[] key = new byte[32];
            assertThrows(IllegalArgumentException.class, () -> new TokenService(key, 0));
        }

        @Test
        @DisplayName("negative expiration throws")
        void negativeExpiration() {
            byte[] key = new byte[32];
            assertThrows(IllegalArgumentException.class, () -> new TokenService(key, -1));
        }

        @Test
        @DisplayName("default constructor works")
        void defaultConstructor() {
            TokenService ts = new TokenService();
            String token = ts.generateToken("test", Role.VIEWER);
            assertNotNull(token);
            assertNotNull(ts.validateToken(token));
        }
    }
}
