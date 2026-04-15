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
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.*;

class JulAdapterTest {

    @Test
    void isAlwaysAvailable() {
        assertTrue(new JulAdapter().isAvailable());
    }

    @Test
    void frameworkNameIsJUL() {
        assertEquals("JUL", new JulAdapter().frameworkName());
    }

    @Test
    void listLoggersReturnsLoggers() {
        JulAdapter adapter = new JulAdapter();
        List<Map<String, Object>> loggers = adapter.listLoggers();
        assertNotNull(loggers);
        // At minimum, ROOT logger should exist
        assertTrue(loggers.stream().anyMatch(l -> "ROOT".equals(l.get("name")) || "".equals(l.get("name"))));
    }

    @Test
    void setLevelChangesJulLogger() {
        JulAdapter adapter = new JulAdapter();
        Map<String, String> result = adapter.setLevel("com.joltvm.test.jul", "DEBUG");
        assertNotNull(result);
        assertEquals("FINE", result.get("newLevel")); // DEBUG maps to FINE in JUL
    }

    @Test
    void setLevelHandlesRootLogger() {
        JulAdapter adapter = new JulAdapter();
        // Save and restore root level
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        Level originalLevel = root.getLevel();
        try {
            Map<String, String> result = adapter.setLevel("ROOT", "WARN");
            assertEquals("WARNING", result.get("newLevel"));
        } finally {
            root.setLevel(originalLevel);
        }
    }

    @Test
    void mapToJulLevelHandsStandardLevels() {
        assertEquals(Level.FINEST, JulAdapter.mapToJulLevel("TRACE"));
        assertEquals(Level.FINE, JulAdapter.mapToJulLevel("DEBUG"));
        assertEquals(Level.INFO, JulAdapter.mapToJulLevel("INFO"));
        assertEquals(Level.WARNING, JulAdapter.mapToJulLevel("WARN"));
        assertEquals(Level.WARNING, JulAdapter.mapToJulLevel("WARNING"));
        assertEquals(Level.SEVERE, JulAdapter.mapToJulLevel("ERROR"));
        assertEquals(Level.OFF, JulAdapter.mapToJulLevel("OFF"));
        assertEquals(Level.ALL, JulAdapter.mapToJulLevel("ALL"));
    }

    @Test
    void mapToJulLevelThrowsForInvalidLevel() {
        assertThrows(IllegalArgumentException.class, () -> JulAdapter.mapToJulLevel("INVALID_LEVEL"));
    }

    @Test
    void logbackAdapterNotAvailableInTestEnv() {
        // Logback may or may not be available depending on test classpath
        // Just verify it doesn't throw
        LogbackAdapter adapter = new LogbackAdapter();
        assertNotNull(adapter.frameworkName());
        // isAvailable() returns a boolean without throwing
        adapter.isAvailable();
    }

    @Test
    void log4j2AdapterNotAvailableInTestEnv() {
        Log4j2Adapter adapter = new Log4j2Adapter();
        assertEquals("Log4j2", adapter.frameworkName());
        // isAvailable() returns a boolean without throwing
        adapter.isAvailable();
    }
}
