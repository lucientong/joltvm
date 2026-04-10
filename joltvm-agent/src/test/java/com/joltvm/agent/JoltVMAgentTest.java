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

package com.joltvm.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link JoltVMAgent}.
 */
class JoltVMAgentTest {

    @AfterEach
    void tearDown() {
        JoltVMAgent.reset();
    }

    @Test
    @DisplayName("premain stores Instrumentation instance")
    void premain_storesInstrumentation() {
        Instrumentation mockInst = createMockInstrumentation();

        JoltVMAgent.premain(null, mockInst);

        assertTrue(InstrumentationHolder.isAvailable());
        assertSame(mockInst, InstrumentationHolder.get());
    }

    @Test
    @DisplayName("agentmain stores Instrumentation instance")
    void agentmain_storesInstrumentation() {
        Instrumentation mockInst = createMockInstrumentation();

        JoltVMAgent.agentmain(null, mockInst);

        assertTrue(InstrumentationHolder.isAvailable());
        assertSame(mockInst, InstrumentationHolder.get());
    }

    @Test
    @DisplayName("premain with agent arguments does not throw")
    void premain_withAgentArgs_doesNotThrow() {
        Instrumentation mockInst = createMockInstrumentation();

        assertDoesNotThrow(() -> JoltVMAgent.premain("port=7758", mockInst));
    }

    @Test
    @DisplayName("double initialization is idempotent")
    void doubleInit_isIdempotent() {
        Instrumentation mockInst = createMockInstrumentation();

        JoltVMAgent.premain(null, mockInst);
        // Second call should not throw or override
        JoltVMAgent.agentmain(null, mockInst);

        assertTrue(InstrumentationHolder.isAvailable());
        assertSame(mockInst, InstrumentationHolder.get());
    }

    private Instrumentation createMockInstrumentation() {
        Instrumentation mockInst = mock(Instrumentation.class);
        when(mockInst.isRedefineClassesSupported()).thenReturn(true);
        when(mockInst.isRetransformClassesSupported()).thenReturn(true);
        when(mockInst.isNativeMethodPrefixSupported()).thenReturn(true);
        return mockInst;
    }

    // ========================================================================
    // parseArgs tests
    // ========================================================================

    @Test
    @DisplayName("parseArgs with null returns empty map")
    void parseArgs_null_returnsEmpty() {
        Map<String, String> result = JoltVMAgent.parseArgs(null);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("parseArgs with blank returns empty map")
    void parseArgs_blank_returnsEmpty() {
        Map<String, String> result = JoltVMAgent.parseArgs("  ");
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("parseArgs with single key=value pair")
    void parseArgs_singlePair() {
        Map<String, String> result = JoltVMAgent.parseArgs("port=7758");
        assertEquals("7758", result.get("port"));
    }

    @Test
    @DisplayName("parseArgs with multiple key=value pairs")
    void parseArgs_multiplePairs() {
        Map<String, String> result = JoltVMAgent.parseArgs("port=7758,debug=true");
        assertEquals("7758", result.get("port"));
        assertEquals("true", result.get("debug"));
    }

    @Test
    @DisplayName("parseArgs trims whitespace")
    void parseArgs_trimsWhitespace() {
        Map<String, String> result = JoltVMAgent.parseArgs(" port = 7758 , debug = true ");
        assertEquals("7758", result.get("port"));
        assertEquals("true", result.get("debug"));
    }
}
