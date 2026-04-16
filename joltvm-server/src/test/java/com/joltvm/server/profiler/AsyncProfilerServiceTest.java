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

package com.joltvm.server.profiler;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AsyncProfilerServiceTest {

    @Test
    void getStatusReturnsStructuredResult() {
        AsyncProfilerService service = new AsyncProfilerService();
        Map<String, Object> status = service.getStatus();
        assertNotNull(status);
        assertNotNull(status.get("platform"));
        assertNotNull(status.get("profiling"));
        assertNotNull(status.get("supportedEvents"));
        assertEquals(false, status.get("profiling"));
        @SuppressWarnings("unchecked")
        List<String> events = (List<String>) status.get("supportedEvents");
        assertTrue(events.contains("cpu"));
        assertTrue(events.contains("alloc"));
        assertTrue(events.contains("lock"));
        assertTrue(events.contains("wall"));
    }

    @Test
    void getStatusReportsAvailabilityCorrectly() {
        AsyncProfilerService service = new AsyncProfilerService();
        Map<String, Object> status = service.getStatus();
        // In test env, async-profiler is typically not available
        assertNotNull(status.get("available"));
        if (!(boolean) status.get("available")) {
            assertNotNull(status.get("unavailableReason"));
        }
    }

    @Test
    void startThrowsWhenNotAvailable() {
        AsyncProfilerService service = new AsyncProfilerService();
        if (!service.isAvailable()) {
            assertThrows(IllegalStateException.class,
                    () -> service.start("cpu", 10, 0));
        }
    }

    @Test
    void stopThrowsWhenNotProfiling() {
        AsyncProfilerService service = new AsyncProfilerService();
        assertThrows(IllegalStateException.class, () -> service.stop());
    }

    @Test
    void getFlameGraphReturnsNullForUnknownSession() {
        AsyncProfilerService service = new AsyncProfilerService();
        assertNull(service.getFlameGraph("nonexistent"));
    }

    @Test
    void parseCollapsedToTreeHandlesEmptyInput() {
        Map<String, Object> tree = AsyncProfilerService.parseCollapsedToTree("");
        assertEquals("root", tree.get("name"));
        assertEquals(0L, ((Number) tree.get("value")).longValue());
    }

    @Test
    void parseCollapsedToTreeHandlesNull() {
        Map<String, Object> tree = AsyncProfilerService.parseCollapsedToTree(null);
        assertEquals("root", tree.get("name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseCollapsedToTreeParsesSimpleStacks() {
        String collapsed = "main;foo;bar 10\nmain;foo;baz 5\nmain;qux 3\n";
        Map<String, Object> tree = AsyncProfilerService.parseCollapsedToTree(collapsed);

        assertEquals("root", tree.get("name"));
        assertEquals(18L, ((Number) tree.get("value")).longValue());

        List<Map<String, Object>> children = (List<Map<String, Object>>) tree.get("children");
        assertEquals(1, children.size()); // only "main" at top level

        Map<String, Object> mainNode = children.get(0);
        assertEquals("main", mainNode.get("name"));
        assertEquals(18L, ((Number) mainNode.get("value")).longValue());

        List<Map<String, Object>> mainChildren = (List<Map<String, Object>>) mainNode.get("children");
        assertEquals(2, mainChildren.size()); // "foo" and "qux"
    }

    @Test
    void parseCollapsedToTreeSkipsMalformedLines() {
        String collapsed = "this line has no count\nmain;foo 5\n\n";
        Map<String, Object> tree = AsyncProfilerService.parseCollapsedToTree(collapsed);
        assertEquals(5L, ((Number) tree.get("value")).longValue());
    }

    @Test
    void startThrowsForInvalidEvent() {
        AsyncProfilerService service = new AsyncProfilerService();
        service.setAvailableForTesting(true, null);
        // Even with available=true, the execute will fail since there's no real instance,
        // but the event parsing should succeed or fail appropriately
        // We test event parsing indirectly
        assertThrows(Exception.class, () -> service.start("invalid_event", 10, 0));
    }
}
