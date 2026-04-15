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

package com.joltvm.server.jvm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JvmInfoServiceTest {

    private JvmInfoService service;

    @BeforeEach
    void setUp() {
        service = new JvmInfoService();
    }

    // ── GC Stats ──

    @Test
    void gcStats_returnsAtLeastOneCollector() {
        Map<String, Object> stats = service.getGcStats();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> collectors = (List<Map<String, Object>>) stats.get("collectors");
        assertFalse(collectors.isEmpty(), "Should have at least one GC collector");
    }

    @Test
    void gcStats_overheadIsNonNegative() {
        Map<String, Object> stats = service.getGcStats();
        double overhead = (double) stats.get("overheadPercent");
        assertTrue(overhead >= 0.0, "GC overhead should be non-negative");
    }

    @Test
    void gcStats_hasUptimeMs() {
        Map<String, Object> stats = service.getGcStats();
        long uptime = (long) stats.get("uptimeMs");
        assertTrue(uptime > 0, "Uptime should be positive");
    }

    @Test
    void gcStats_collectorHasRequiredFields() {
        Map<String, Object> stats = service.getGcStats();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> collectors = (List<Map<String, Object>>) stats.get("collectors");
        Map<String, Object> first = collectors.get(0);
        assertNotNull(first.get("name"));
        assertNotNull(first.get("collectionCount"));
        assertNotNull(first.get("collectionTimeMs"));
        assertNotNull(first.get("memoryPools"));
    }

    // ── System Properties ──

    @Test
    void sysProps_containsJavaVersion() {
        Map<String, Object> result = service.getSystemProperties();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> props = (List<Map<String, String>>) result.get("properties");
        boolean hasJavaVersion = props.stream()
                .anyMatch(p -> "java.version".equals(p.get("key")));
        assertTrue(hasJavaVersion, "Should contain java.version");
    }

    @Test
    void sysProps_redactsSensitiveKeys() {
        // Set a sensitive system property for testing
        System.setProperty("test.secret.password", "super-secret-123");
        try {
            Map<String, Object> result = service.getSystemProperties();
            @SuppressWarnings("unchecked")
            List<Map<String, String>> props = (List<Map<String, String>>) result.get("properties");
            Map<String, String> sensitiveEntry = props.stream()
                    .filter(p -> "test.secret.password".equals(p.get("key")))
                    .findFirst().orElse(null);
            assertNotNull(sensitiveEntry);
            assertEquals("******", sensitiveEntry.get("value"), "Sensitive values should be redacted");
        } finally {
            System.clearProperty("test.secret.password");
        }
    }

    @Test
    void sysProps_hasCount() {
        Map<String, Object> result = service.getSystemProperties();
        int count = (int) result.get("count");
        assertTrue(count > 0, "Should have at least one property");
    }

    // ── Environment Variables ──

    @Test
    void sysEnv_returnsNonEmpty() {
        Map<String, Object> result = service.getEnvironmentVariables();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> vars = (List<Map<String, String>>) result.get("variables");
        assertFalse(vars.isEmpty(), "Should have at least one env variable");
    }

    @Test
    void sysEnv_hasCount() {
        Map<String, Object> result = service.getEnvironmentVariables();
        int count = (int) result.get("count");
        assertTrue(count > 0);
    }

    // ── Classpath ──

    @Test
    void classpath_returnsNonEmpty() {
        Map<String, Object> result = service.getClasspath();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        assertFalse(entries.isEmpty(), "Classpath should not be empty");
    }

    @Test
    void classpath_entryHasPathAndType() {
        Map<String, Object> result = service.getClasspath();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) result.get("entries");
        Map<String, Object> first = entries.get(0);
        assertNotNull(first.get("path"));
        assertNotNull(first.get("type"));
        assertNotNull(first.get("exists"));
    }

    // ── Sensitive key detection ──

    @Test
    void sensitiveKey_detectsPasswordVariants() {
        assertTrue(JvmInfoService.isSensitiveKey("DB_PASSWORD"));
        assertTrue(JvmInfoService.isSensitiveKey("db.password"));
        assertTrue(JvmInfoService.isSensitiveKey("ADMIN_PASSWD"));
    }

    @Test
    void sensitiveKey_detectsSecretAndToken() {
        assertTrue(JvmInfoService.isSensitiveKey("AWS_SECRET_ACCESS_KEY"));
        assertTrue(JvmInfoService.isSensitiveKey("GITHUB_TOKEN"));
        assertTrue(JvmInfoService.isSensitiveKey("api_key"));
        assertTrue(JvmInfoService.isSensitiveKey("API-KEY"));
    }

    @Test
    void sensitiveKey_doesNotFlagNormalKeys() {
        assertFalse(JvmInfoService.isSensitiveKey("JAVA_HOME"));
        assertFalse(JvmInfoService.isSensitiveKey("java.version"));
        assertFalse(JvmInfoService.isSensitiveKey("os.name"));
        assertFalse(JvmInfoService.isSensitiveKey("PATH"));
    }
}
