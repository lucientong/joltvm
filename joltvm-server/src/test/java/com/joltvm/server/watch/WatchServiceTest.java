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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchServiceTest {

    private WatchService service = new WatchService();

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void listWatchesReturnsEmptyInitially() {
        Map<String, Object> result = service.listWatches();
        assertEquals(0, result.get("count"));
        assertEquals(WatchService.MAX_CONCURRENT_WATCHES, result.get("maxConcurrent"));
    }

    @Test
    void startWatchRejectsBlankClassPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startWatch("", "*", null, 100, 60000));
    }

    @Test
    void startWatchRejectsNullClassPattern() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startWatch(null, "*", null, 100, 60000));
    }

    @Test
    void stopWatchReturnsNullForUnknownSession() {
        assertNull(service.stopWatch("nonexistent"));
    }

    @Test
    void deleteWatchReturnsFalseForUnknownSession() {
        assertFalse(service.deleteWatch("nonexistent"));
    }

    @Test
    void getRecordsReturnsNullForUnknownSession() {
        assertNull(service.getRecords("nonexistent", 0));
    }

    @Test
    void recordInvocationDoesNotThrowWhenNoSessions() {
        // Should silently do nothing
        assertDoesNotThrow(() ->
                WatchService.recordInvocation("com.test", "foo",
                        new String[]{"a"}, "result", null, null, 1000));
    }
}
