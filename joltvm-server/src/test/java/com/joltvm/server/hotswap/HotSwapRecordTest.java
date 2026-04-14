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

package com.joltvm.server.hotswap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HotSwapRecord}.
 */
@DisplayName("HotSwapRecord")
class HotSwapRecordTest {

    @Test
    @DisplayName("record stores all fields correctly")
    void recordStoresAllFields() {
        Instant now = Instant.now();
        HotSwapRecord record = new HotSwapRecord(
                "abc123",
                "com.test.MyClass",
                HotSwapRecord.Action.HOTSWAP,
                HotSwapRecord.Status.SUCCESS,
                "Successfully redefined",
                now
        );

        assertEquals("abc123", record.id());
        assertEquals("com.test.MyClass", record.className());
        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.SUCCESS, record.status());
        assertEquals("Successfully redefined", record.message());
        assertEquals(now, record.timestamp());
    }

    @Test
    @DisplayName("9-arg constructor stores operator, reason and diff")
    void fullConstructorStoresAllFields() {
        Instant now = Instant.now();
        HotSwapRecord record = new HotSwapRecord(
                "xyz789",
                "com.test.MyClass",
                HotSwapRecord.Action.HOTSWAP,
                HotSwapRecord.Status.SUCCESS,
                "Redefined OK",
                now,
                "admin",
                "fix bug #42",
                "Original: 100 bytes → New: 120 bytes"
        );

        assertEquals("xyz789", record.id());
        assertEquals("com.test.MyClass", record.className());
        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.SUCCESS, record.status());
        assertEquals("Redefined OK", record.message());
        assertEquals(now, record.timestamp());
        assertEquals("admin", record.operator());
        assertEquals("fix bug #42", record.reason());
        assertEquals("Original: 100 bytes → New: 120 bytes", record.diff());
    }

    @Test
    @DisplayName("6-arg constructor sets operator, reason and diff to null")
    void backwardCompatibleConstructorSetsNulls() {
        Instant now = Instant.now();
        HotSwapRecord record = new HotSwapRecord(
                "id1", "com.test.Old",
                HotSwapRecord.Action.ROLLBACK,
                HotSwapRecord.Status.FAILED,
                "No backup", now
        );

        assertNull(record.operator());
        assertNull(record.reason());
        assertNull(record.diff());
    }

    @Test
    @DisplayName("Action enum values exist")
    void actionEnumValues() {
        assertEquals(2, HotSwapRecord.Action.values().length);
        assertNotNull(HotSwapRecord.Action.HOTSWAP);
        assertNotNull(HotSwapRecord.Action.ROLLBACK);
    }

    @Test
    @DisplayName("Status enum values exist")
    void statusEnumValues() {
        assertEquals(2, HotSwapRecord.Status.values().length);
        assertNotNull(HotSwapRecord.Status.SUCCESS);
        assertNotNull(HotSwapRecord.Status.FAILED);
    }
}
