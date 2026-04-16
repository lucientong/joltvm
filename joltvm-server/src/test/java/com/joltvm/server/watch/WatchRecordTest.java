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

package com.joltvm.server.watch;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchRecordTest {

    @Test
    void toMapContainsAllFields() {
        WatchRecord record = new WatchRecord(
                Instant.parse("2026-04-16T10:00:00Z"),
                "com.test.Service", "handle",
                "main", 1,
                new String[]{"arg1", "arg2"},
                "result",
                null, null,
                5_000_000, "AFTER");

        Map<String, Object> map = record.toMap();
        assertEquals("com.test.Service", map.get("className"));
        assertEquals("handle", map.get("methodName"));
        assertEquals("main", map.get("threadName"));
        assertEquals(1L, map.get("threadId"));
        assertEquals("result", map.get("returnValue"));
        assertEquals(5_000_000L, map.get("durationNanos"));
        assertEquals(5.0, (double) map.get("durationMs"), 0.01);
        assertEquals("AFTER", map.get("watchPoint"));
        assertFalse(map.containsKey("exceptionType"));
    }

    @Test
    void toMapContainsExceptionFields() {
        WatchRecord record = new WatchRecord(
                Instant.now(), "com.test", "m", "t", 1,
                null, null,
                "java.lang.NullPointerException", "oops",
                100, "EXCEPTION");

        Map<String, Object> map = record.toMap();
        assertEquals("java.lang.NullPointerException", map.get("exceptionType"));
        assertEquals("oops", map.get("exceptionMessage"));
        assertEquals("EXCEPTION", map.get("watchPoint"));
    }
}
