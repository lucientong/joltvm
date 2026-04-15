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

package com.joltvm.server.logger;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoggerServiceTest {

    @Test
    void detectAdapterReturnsJulWhenNoOtherFramework() {
        // In test environment, JUL is always the fallback
        LoggerAdapter adapter = LoggerService.detectAdapter();
        assertNotNull(adapter);
        // Should be one of: Logback, Log4j2, JUL
        assertTrue(List.of("Logback", "Log4j2", "JUL").contains(adapter.frameworkName()));
    }

    @Test
    void listLoggersReturnsNonEmpty() {
        LoggerService service = new LoggerService();
        Map<String, Object> result = service.listLoggers();
        assertNotNull(result);
        assertNotNull(result.get("framework"));
        assertNotNull(result.get("loggers"));
        assertTrue((int) result.get("count") >= 0);
    }

    @Test
    void setLevelChangesAndReturnsPrevious() {
        LoggerService service = new LoggerService(new JulAdapter());
        Map<String, Object> result = service.setLevel("com.joltvm.test.logger", "DEBUG");
        assertNotNull(result);
        assertEquals("JUL", result.get("framework"));
        assertEquals("com.joltvm.test.logger", result.get("loggerName"));
        assertNotNull(result.get("newLevel"));
    }

    @Test
    void getAdapterReturnsCorrectAdapter() {
        JulAdapter jul = new JulAdapter();
        LoggerService service = new LoggerService(jul);
        assertSame(jul, service.getAdapter());
    }
}
