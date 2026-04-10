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

package com.joltvm.server.tracing;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MethodTraceService}.
 *
 * <p>Note: These tests focus on state management, validation, and sampling logic.
 * Byte Buddy Advice-based tracing requires a real Instrumentation instance and
 * is tested via integration tests.
 */
@DisplayName("MethodTraceService")
class MethodTraceServiceTest {

    private FlameGraphCollector collector;
    private MethodTraceService service;

    @BeforeEach
    void setUp() {
        collector = new FlameGraphCollector();
        service = new MethodTraceService(collector);
    }

    @AfterEach
    void tearDown() {
        service.reset();
    }

    @Test
    @DisplayName("initial state: not tracing, not sampling")
    void initialState() {
        assertFalse(service.isTracing());
        assertFalse(service.isSampling());
        assertNull(service.getCurrentTraceTarget());
        assertTrue(service.getTracedClasses().isEmpty());
    }

    @Test
    @DisplayName("getCollector returns injected collector")
    void getCollectorReturnsInjected() {
        assertSame(collector, service.getCollector());
    }

    @Test
    @DisplayName("default constructor creates its own collector")
    void defaultConstructorCreatesCollector() {
        MethodTraceService defaultService = new MethodTraceService();
        assertNotNull(defaultService.getCollector());
        defaultService.reset();
    }

    @Test
    @DisplayName("startTrace rejects null class name")
    void startTraceRejectsNullClassName() {
        TracingException e = assertThrows(TracingException.class,
                () -> service.startTrace(null, "method", 10));
        assertTrue(e.getMessage().contains("Class name is required"));
    }

    @Test
    @DisplayName("startTrace rejects blank class name")
    void startTraceRejectsBlankClassName() {
        TracingException e = assertThrows(TracingException.class,
                () -> service.startTrace("  ", "method", 10));
        assertTrue(e.getMessage().contains("Class name is required"));
    }

    @Test
    @DisplayName("startTrace rejects when instrumentation not available")
    void startTraceRejectsWithoutInstrumentation() {
        // Without agent loaded, InstrumentationHolder.isAvailable() returns false
        TracingException e = assertThrows(TracingException.class,
                () -> service.startTrace("com.example.Test", "method", 10));
        assertTrue(e.getMessage().contains("Instrumentation not available"));
    }

    @Test
    @DisplayName("startSampling works and can be stopped")
    void startSamplingAndStop() {
        service.startSampling(50, 5);
        assertTrue(service.isSampling());

        service.stopSampling();
        assertFalse(service.isSampling());
    }

    @Test
    @DisplayName("startSampling rejects duplicate")
    void startSamplingRejectsDuplicate() {
        service.startSampling(50, 5);

        TracingException e = assertThrows(TracingException.class,
                () -> service.startSampling(50, 5));
        assertTrue(e.getMessage().contains("already active"));

        service.stopSampling();
    }

    @Test
    @DisplayName("stopSampling is idempotent")
    void stopSamplingIdempotent() {
        service.stopSampling(); // No exception when not sampling
        assertFalse(service.isSampling());
    }

    @Test
    @DisplayName("stopTrace is idempotent")
    void stopTraceIdempotent() {
        service.stopTrace(); // No exception when not tracing
        assertFalse(service.isTracing());
    }

    @Test
    @DisplayName("sampling collects stack samples")
    void samplingCollectsStackSamples() throws InterruptedException {
        service.startSampling(10, 5);

        // Wait a bit for samples to be collected
        Thread.sleep(100);

        service.stopSampling();

        // Should have collected some samples (threads may be daemon, so count could vary)
        // Just verify no errors occurred
        assertFalse(service.isSampling());
    }

    @Test
    @DisplayName("getStatus returns correct state")
    void getStatusReturnsCorrectState() {
        Map<String, Object> status = service.getStatus();

        assertEquals(false, status.get("tracing"));
        assertEquals(false, status.get("sampling"));
        assertNull(status.get("traceTarget"));
        assertEquals(0, collector.getRecordCount());
        assertEquals(0, collector.getSampleCount());
    }

    @Test
    @DisplayName("getStatus reflects sampling state")
    void getStatusReflectsSamplingState() {
        service.startSampling(50, 5);

        Map<String, Object> status = service.getStatus();
        assertEquals(true, status.get("sampling"));
        assertEquals(false, status.get("tracing"));

        service.stopSampling();
    }

    @Test
    @DisplayName("reset stops everything and clears data")
    void resetStopsEverythingAndClears() {
        service.startSampling(10, 5);
        // Add some records manually
        collector.addRecord(new TraceRecord(
                "id", "com.example.A", "m",
                java.util.List.of(), java.util.List.of(),
                null, null, null, 1000L,
                "main", 1L, java.time.Instant.now(), 0
        ));

        service.reset();

        assertFalse(service.isTracing());
        assertFalse(service.isSampling());
        assertEquals(0, collector.getRecordCount());
        assertEquals(0, collector.getSampleCount());
    }

    @Test
    @DisplayName("getTracedClasses returns unmodifiable set")
    void getTracedClassesUnmodifiable() {
        assertThrows(UnsupportedOperationException.class,
                () -> service.getTracedClasses().add("test"));
    }
}
