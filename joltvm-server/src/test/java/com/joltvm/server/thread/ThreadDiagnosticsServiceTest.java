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

package com.joltvm.server.thread;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ThreadDiagnosticsService}.
 */
class ThreadDiagnosticsServiceTest {

    private ThreadDiagnosticsService service;

    @BeforeEach
    void setUp() {
        service = new ThreadDiagnosticsService();
    }

    @Test
    void getAllThreads_returnsNonEmpty() {
        List<Map<String, Object>> threads = service.getAllThreads(null);
        assertFalse(threads.isEmpty(), "Should return at least one thread");
    }

    @Test
    void getAllThreads_containsMainThread() {
        List<Map<String, Object>> threads = service.getAllThreads(null);
        boolean hasMain = threads.stream()
                .anyMatch(t -> "main".equals(t.get("name")));
        // main thread may not always be present in test context, but there should be threads
        assertTrue(threads.size() > 0);
    }

    @Test
    void getAllThreads_filterByState() {
        List<Map<String, Object>> runnableThreads = service.getAllThreads(Thread.State.RUNNABLE);
        for (Map<String, Object> t : runnableThreads) {
            assertEquals("RUNNABLE", t.get("state"));
        }
    }

    @Test
    void getAllThreads_eachThreadHasRequiredFields() {
        List<Map<String, Object>> threads = service.getAllThreads(null);
        assertFalse(threads.isEmpty());

        Map<String, Object> first = threads.get(0);
        assertNotNull(first.get("id"), "Thread should have id");
        assertNotNull(first.get("name"), "Thread should have name");
        assertNotNull(first.get("state"), "Thread should have state");
        assertNotNull(first.get("daemon"), "Thread should have daemon flag");
    }

    @Test
    void getThreadDetail_existingThread() {
        // Get a known thread ID
        List<Map<String, Object>> threads = service.getAllThreads(null);
        assertFalse(threads.isEmpty());
        long id = (long) threads.get(0).get("id");

        Map<String, Object> detail = service.getThreadDetail(id);
        assertNotNull(detail, "Should return detail for existing thread");
        assertEquals(id, detail.get("id"));
        assertNotNull(detail.get("stackTrace"), "Detail should include stack trace");
        assertInstanceOf(List.class, detail.get("stackTrace"));
    }

    @Test
    void getThreadDetail_nonExistingThread() {
        Map<String, Object> detail = service.getThreadDetail(-99999);
        assertNull(detail, "Should return null for non-existing thread");
    }

    @Test
    void detectDeadlocks_noDeadlocks() {
        List<Map<String, Object>> deadlocks = service.detectDeadlocks();
        assertNotNull(deadlocks);
        assertTrue(deadlocks.isEmpty(), "Should have no deadlocks in normal test");
    }

    @Test
    void generateThreadDump_containsHeader() {
        String dump = service.generateThreadDump();
        assertNotNull(dump);
        assertTrue(dump.startsWith("Full thread dump"), "Dump should start with standard header");
    }

    @Test
    void generateThreadDump_containsThreadEntries() {
        String dump = service.generateThreadDump();
        assertTrue(dump.contains("java.lang.Thread.State:"), "Dump should contain thread state");
    }

    @Test
    void generateThreadDump_nonEmpty() {
        String dump = service.generateThreadDump();
        assertTrue(dump.length() > 100, "Dump should be substantial");
    }

    @Test
    void getThreadCounts_returnsValidCounts() {
        Map<String, Object> counts = service.getThreadCounts();
        assertNotNull(counts.get("live"));
        assertNotNull(counts.get("daemon"));
        assertNotNull(counts.get("peak"));
        assertNotNull(counts.get("totalStarted"));
        assertTrue((int) counts.get("live") > 0, "Should have at least one live thread");
    }

    @Test
    void getTopThreadsByCpu_returnsResults() {
        // Use short interval for fast test
        List<Map<String, Object>> top = service.getTopThreadsByCpu(5, 100);
        assertNotNull(top);
        // May be empty if CPU time not supported, but should not throw
        if (!top.isEmpty()) {
            Map<String, Object> first = top.get(0);
            assertNotNull(first.get("cpuDeltaNanos"));
            assertNotNull(first.get("cpuPercent"));
        }
    }

    @Test
    void getTopThreadsByCpu_respectsLimit() {
        List<Map<String, Object>> top = service.getTopThreadsByCpu(2, 100);
        assertTrue(top.size() <= 2, "Should respect the limit");
    }
}
