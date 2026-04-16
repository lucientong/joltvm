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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchSessionTest {

    @Test
    void sessionHasUniqueId() {
        WatchSession s1 = new WatchSession("com.test", "*", null, 100, 60000);
        WatchSession s2 = new WatchSession("com.test", "*", null, 100, 60000);
        assertNotEquals(s1.getId(), s2.getId());
    }

    @Test
    void addRecordStoresRecord() {
        WatchSession session = new WatchSession("test", "com.test", "*", null, 100, 60000);
        WatchRecord record = new WatchRecord(Instant.now(), "com.test", "foo",
                "main", 1, new String[]{"arg1"}, "result", null, null, 1000, "AFTER");
        assertTrue(session.addRecord(record));
        assertEquals(1, session.getRecords().size());
    }

    @Test
    void addRecordEvictsOldestWhenFull() {
        WatchSession session = new WatchSession("test", "com.test", "*", null, 3, 60000);
        for (int i = 0; i < 5; i++) {
            session.addRecord(new WatchRecord(Instant.now(), "com.test", "m" + i,
                    "main", 1, null, null, null, null, i * 100, "AFTER"));
        }
        assertEquals(3, session.getRecords().size());
    }

    @Test
    void stoppedSessionRejectsRecords() {
        WatchSession session = new WatchSession("test", "com.test", "*", null, 100, 60000);
        session.stop();
        assertFalse(session.addRecord(new WatchRecord(Instant.now(), "com.test", "foo",
                "main", 1, null, null, null, null, 100, "AFTER")));
    }

    @Test
    void toSummaryMapContainsAllFields() {
        WatchSession session = new WatchSession("test", "com.test", "handle", "#cost > 0", 50, 30000);
        Map<String, Object> map = session.toSummaryMap();
        assertEquals("test", map.get("id"));
        assertEquals("com.test", map.get("classPattern"));
        assertEquals("handle", map.get("methodPattern"));
        assertEquals("#cost > 0", map.get("conditionExpr"));
        assertEquals(50, map.get("maxRecords"));
        assertTrue((boolean) map.get("active"));
    }

    @Test
    void getRecordsSinceReturnsSubset() {
        WatchSession session = new WatchSession("test", "com.test", "*", null, 100, 60000);
        for (int i = 0; i < 5; i++) {
            session.addRecord(new WatchRecord(Instant.now(), "com.test", "m" + i,
                    "main", 1, null, null, null, null, i, "AFTER"));
        }
        List<WatchRecord> since2 = session.getRecordsSince(2);
        assertEquals(3, since2.size());
    }

    @Test
    void maxRecordsClamped() {
        WatchSession session = new WatchSession("test", "com.test", "*", null, 9999, 60000);
        Map<String, Object> map = session.toSummaryMap();
        assertEquals(1000, map.get("maxRecords"));
    }
}
