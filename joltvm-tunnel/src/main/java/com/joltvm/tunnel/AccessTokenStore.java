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

package com.joltvm.tunnel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Access-token store for Tunnel Server HTTP / dashboard requests.
 *
 * <p>Separate from agent registration tokens. When empty, access is allowed
 * (dev mode) but callers should log a warning.
 */
public final class AccessTokenStore {

    private final Set<String> tokens = ConcurrentHashMap.newKeySet();

    public void addToken(String token) {
        if (token != null && !token.isBlank()) {
            tokens.add(token);
        }
    }

    public boolean isConfigured() {
        return !tokens.isEmpty();
    }

    /**
     * Validates a bearer / header token with constant-time comparison.
     *
     * @param candidate the provided token (may be null)
     * @return {@code true} if access is allowed
     */
    public boolean isValid(String candidate) {
        if (tokens.isEmpty()) {
            return true; // dev mode
        }
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        for (String expected : tokens) {
            if (constantTimeEquals(expected, candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] left = a.getBytes(StandardCharsets.UTF_8);
        byte[] right = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(left, right);
    }
}
