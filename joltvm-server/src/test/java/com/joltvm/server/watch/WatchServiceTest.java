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

import com.joltvm.server.ognl.OgnlService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WatchServiceTest {

    private OgnlService ognlService = new OgnlService();
    private WatchService service = new WatchService(ognlService);

    @AfterEach
    void tearDown() {
        service.shutdown();
        ognlService.shutdown();
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
    void startWatchRejectsInvalidConditionExpr() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.startWatch("com.test.Svc", "*", "@@@invalid[[[", 100, 60000));
        assertTrue(ex.getMessage().contains("conditionExpr")
                || ex.getMessage().toLowerCase().contains("parse")
                || ex.getMessage().toLowerCase().contains("expression"));
    }

    @Test
    void startWatchRejectsDangerousConditionExpr() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startWatch("com.test.Svc", "*",
                        "@java.lang.Runtime@getRuntime().exec('id')", 100, 60000));
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
        assertDoesNotThrow(() ->
                WatchService.recordInvocation("com.test", "foo",
                        new Object[]{"a"}, "result", null, null, String.class, 1000));
    }

    @Test
    void recordInvocationWithoutConditionRecordsAll() {
        WatchSession session = new WatchSession("nocond", "com.test.Svc", "foo", null, 100, 60_000);
        service.registerSessionForTest(session);

        WatchService.recordInvocation("com.test.Svc", "foo",
                new Object[]{"x"}, "ok", null, this, String.class, 500L);

        assertEquals(1, session.getTotalSeen());
        assertEquals(1, session.getRecords().size());
        assertEquals(1, session.getTotalMatched());
    }

    @Test
    void recordInvocationConditionTrueAddsRecord() {
        WatchSession session = new WatchSession("truecond", "com.test.Svc", "foo",
                "#cost > 1000", 100, 60_000);
        session.setCompiledCondition(ognlService.compileCondition("#cost > 1000"));
        service.registerSessionForTest(session);

        WatchService.recordInvocation("com.test.Svc", "foo",
                new Object[]{}, "ok", null, null, String.class, 2_000_000L);

        assertEquals(1, session.getTotalSeen());
        assertEquals(1, session.getRecords().size());
    }

    @Test
    void recordInvocationConditionFalseSkipsRecordButCountsSeen() {
        WatchSession session = new WatchSession("falsecond", "com.test.Svc", "foo",
                "#cost > 1000000", 100, 60_000);
        session.setCompiledCondition(ognlService.compileCondition("#cost > 1000000"));
        service.registerSessionForTest(session);

        WatchService.recordInvocation("com.test.Svc", "foo",
                new Object[]{}, "ok", null, null, String.class, 100L);

        assertEquals(1, session.getTotalSeen());
        assertEquals(0, session.getRecords().size());
        assertEquals(0, session.getTotalMatched());
    }

    @Test
    void recordInvocationThrowExpCondition() {
        WatchSession session = new WatchSession("exc", "com.test.Svc", "foo",
                "#throwExp != null", 100, 60_000);
        session.setCompiledCondition(ognlService.compileCondition("#throwExp != null"));
        service.registerSessionForTest(session);

        WatchService.recordInvocation("com.test.Svc", "foo",
                new Object[]{}, null, null, null, String.class, 10L);
        assertEquals(0, session.getRecords().size());

        WatchService.recordInvocation("com.test.Svc", "foo",
                new Object[]{}, null, new RuntimeException("boom"), null, String.class, 10L);
        assertEquals(2, session.getTotalSeen());
        assertEquals(1, session.getRecords().size());
        assertEquals("EXCEPTION", session.getRecords().get(0).getWatchPoint());
    }

    @Test
    void toSummaryMapIncludesTotalSeen() {
        WatchSession session = new WatchSession("sum", "com.test.Svc", "*", null, 100, 60_000);
        service.registerSessionForTest(session);
        WatchService.recordInvocation("com.test.Svc", "bar",
                null, null, null, null, String.class, 1L);

        Map<String, Object> summary = session.toSummaryMap();
        assertEquals(1, summary.get("totalSeen"));
        assertEquals(1, summary.get("totalMatched"));
    }
}
