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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TraceRecord}.
 */
@DisplayName("TraceRecord")
class TraceRecordTest {

    @Test
    @DisplayName("durationMs converts nanoseconds to milliseconds")
    void durationMsConvertsCorrectly() {
        TraceRecord record = createRecord(5_000_000L, null, null);
        assertEquals(5.0, record.durationMs(), 0.001);
    }

    @Test
    @DisplayName("durationMs with sub-millisecond precision")
    void durationMsSubMillisecondPrecision() {
        TraceRecord record = createRecord(1_500_000L, null, null);
        assertEquals(1.5, record.durationMs(), 0.001);
    }

    @Test
    @DisplayName("hasException returns true when exception type is set")
    void hasExceptionWhenExceptionSet() {
        TraceRecord record = createRecord(1000L, "java.lang.RuntimeException", "Test error");
        assertTrue(record.hasException());
    }

    @Test
    @DisplayName("hasException returns false when no exception")
    void hasExceptionWhenNoException() {
        TraceRecord record = createRecord(1000L, null, null);
        assertFalse(record.hasException());
    }

    @Test
    @DisplayName("signature formats correctly")
    void signatureFormatsCorrectly() {
        TraceRecord record = new TraceRecord(
                "test1", "com.example.MyService", "handleRequest",
                List.of("String", "int"), List.of("hello", "42"),
                "result", null, null, 1000L,
                "main", 1L, Instant.now(), 0
        );
        assertEquals("com.example.MyService#handleRequest(String, int)", record.signature());
    }

    @Test
    @DisplayName("signature with no parameters")
    void signatureWithNoParams() {
        TraceRecord record = new TraceRecord(
                "test2", "com.example.MyService", "init",
                List.of(), List.of(),
                null, null, null, 1000L,
                "main", 1L, Instant.now(), 0
        );
        assertEquals("com.example.MyService#init()", record.signature());
    }

    @Test
    @DisplayName("record fields are accessible")
    void recordFieldsAccessible() {
        Instant now = Instant.now();
        TraceRecord record = new TraceRecord(
                "abc123", "com.example.Foo", "bar",
                List.of("String"), List.of("test"),
                "OK", null, null, 2_000_000L,
                "worker-1", 42L, now, 1
        );

        assertEquals("abc123", record.id());
        assertEquals("com.example.Foo", record.className());
        assertEquals("bar", record.methodName());
        assertEquals(List.of("String"), record.parameterTypes());
        assertEquals(List.of("test"), record.arguments());
        assertEquals("OK", record.returnValue());
        assertNull(record.exceptionType());
        assertNull(record.exceptionMessage());
        assertEquals(2_000_000L, record.durationNanos());
        assertEquals("worker-1", record.threadName());
        assertEquals(42L, record.threadId());
        assertEquals(now, record.timestamp());
        assertEquals(1, record.depth());
    }

    private TraceRecord createRecord(long durationNanos, String exType, String exMsg) {
        return new TraceRecord(
                "id1", "com.example.TestClass", "testMethod",
                List.of("String"), List.of("arg1"),
                "result", exType, exMsg, durationNanos,
                "main", 1L, Instant.now(), 0
        );
    }
}
