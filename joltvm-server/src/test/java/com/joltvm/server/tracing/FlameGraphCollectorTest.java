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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FlameGraphCollector}.
 */
@DisplayName("FlameGraphCollector")
class FlameGraphCollectorTest {

    private FlameGraphCollector collector;

    @BeforeEach
    void setUp() {
        collector = new FlameGraphCollector();
    }

    @Test
    @DisplayName("addRecord stores and retrieves records")
    void addRecordStoresRecords() {
        TraceRecord record = createRecord("com.example.A", "method1", 1000L);
        collector.addRecord(record);

        assertEquals(1, collector.getRecordCount());
        List<TraceRecord> records = collector.getRecords();
        assertEquals(1, records.size());
        assertEquals("com.example.A", records.get(0).className());
    }

    @Test
    @DisplayName("getRecords returns newest first")
    void getRecordsNewestFirst() {
        collector.addRecord(createRecord("com.example.A", "first", 1000L));
        collector.addRecord(createRecord("com.example.B", "second", 2000L));
        collector.addRecord(createRecord("com.example.C", "third", 3000L));

        List<TraceRecord> records = collector.getRecords();
        assertEquals(3, records.size());
        assertEquals("com.example.C", records.get(0).className());
        assertEquals("com.example.A", records.get(2).className());
    }

    @Test
    @DisplayName("getRecords with limit returns capped list")
    void getRecordsWithLimit() {
        for (int i = 0; i < 10; i++) {
            collector.addRecord(createRecord("com.example.Class" + i, "method", 1000L));
        }

        List<TraceRecord> limited = collector.getRecords(3);
        assertEquals(3, limited.size());
    }

    @Test
    @DisplayName("getRecords with limit larger than total returns all")
    void getRecordsWithLargeLimitReturnsAll() {
        collector.addRecord(createRecord("com.example.A", "m", 1000L));
        collector.addRecord(createRecord("com.example.B", "m", 2000L));

        List<TraceRecord> limited = collector.getRecords(100);
        assertEquals(2, limited.size());
    }

    @Test
    @DisplayName("getRecords returns unmodifiable list")
    void getRecordsReturnsUnmodifiable() {
        collector.addRecord(createRecord("com.example.A", "m", 1000L));
        List<TraceRecord> records = collector.getRecords();
        assertThrows(UnsupportedOperationException.class, () -> records.add(null));
    }

    @Test
    @DisplayName("records are trimmed to MAX_RECORDS")
    void recordsTrimmedToMax() {
        for (int i = 0; i < FlameGraphCollector.MAX_RECORDS + 50; i++) {
            collector.addRecord(createRecord("com.example.Class" + i, "m", 1000L));
        }
        assertTrue(collector.getRecordCount() <= FlameGraphCollector.MAX_RECORDS);
    }

    @Test
    @DisplayName("addStackSample stores samples")
    void addStackSampleStoresSamples() {
        StackTraceElement[] stack = new StackTraceElement[]{
                new StackTraceElement("com.example.A", "method1", "A.java", 10),
                new StackTraceElement("com.example.B", "method2", "B.java", 20)
        };
        collector.addStackSample(stack);

        assertEquals(1, collector.getSampleCount());
    }

    @Test
    @DisplayName("samples are trimmed to MAX_SAMPLES")
    void samplesTrimmedToMax() {
        StackTraceElement[] stack = new StackTraceElement[]{
                new StackTraceElement("com.example.A", "m", "A.java", 1)
        };
        for (int i = 0; i < FlameGraphCollector.MAX_SAMPLES + 50; i++) {
            collector.addStackSample(stack);
        }
        assertTrue(collector.getSampleCount() <= FlameGraphCollector.MAX_SAMPLES);
    }

    @Test
    @DisplayName("buildFlameGraphFromRecords creates tree from records")
    void buildFlameGraphFromRecords() {
        collector.addRecord(createRecord("com.example.A", "method1", 10_000_000L)); // 10ms = 10000 micros
        collector.addRecord(createRecord("com.example.A", "method1", 5_000_000L));  // 5ms = 5000 micros
        collector.addRecord(createRecord("com.example.B", "method2", 3_000_000L));  // 3ms = 3000 micros

        FlameGraphNode root = collector.buildFlameGraphFromRecords();
        assertEquals("root", root.getName());
        assertEquals(2, root.getChildren().size());

        // A#method1 should have 15000 micros
        FlameGraphNode nodeA = root.getChildren().stream()
                .filter(c -> c.getName().equals("com.example.A#method1"))
                .findFirst().orElseThrow();
        assertEquals(15000, nodeA.getValue());

        // B#method2 should have 3000 micros
        FlameGraphNode nodeB = root.getChildren().stream()
                .filter(c -> c.getName().equals("com.example.B#method2"))
                .findFirst().orElseThrow();
        assertEquals(3000, nodeB.getValue());
    }

    @Test
    @DisplayName("buildFlameGraphFromRecords returns empty tree when no records")
    void buildFlameGraphFromRecordsEmpty() {
        FlameGraphNode root = collector.buildFlameGraphFromRecords();
        assertEquals("root", root.getName());
        assertEquals(0, root.getValue());
        assertTrue(root.getChildren().isEmpty());
    }

    @Test
    @DisplayName("buildFlameGraphFromSamples creates tree from stack traces")
    void buildFlameGraphFromSamples() {
        StackTraceElement[] stack = new StackTraceElement[]{
                new StackTraceElement("com.example.A", "leaf", "A.java", 10),
                new StackTraceElement("com.example.B", "middle", "B.java", 20),
                new StackTraceElement("com.example.C", "root", "C.java", 30)
        };
        collector.addStackSample(stack);
        collector.addStackSample(stack);

        FlameGraphNode root = collector.buildFlameGraphFromSamples();
        assertEquals("root", root.getName());
        assertEquals(2, root.getValue());

        // Stack is reversed: C (bottom) → B → A (top)
        assertEquals(1, root.getChildren().size());
        FlameGraphNode nodeC = root.getChildren().get(0);
        assertEquals("com.example.C#root", nodeC.getName());
        assertEquals(2, nodeC.getValue());
    }

    @Test
    @DisplayName("getFlameGraphData prefers samples over records")
    void getFlameGraphDataPrefersSamples() {
        collector.addRecord(createRecord("com.example.A", "m", 1000L));
        StackTraceElement[] stack = new StackTraceElement[]{
                new StackTraceElement("com.example.X", "sample", "X.java", 1)
        };
        collector.addStackSample(stack);

        Map<String, Object> data = collector.getFlameGraphData();
        // When samples exist, should use sample-based graph (value=1 per sample)
        assertEquals("root", data.get("name"));
        assertEquals(1L, data.get("value"));
    }

    @Test
    @DisplayName("getFlameGraphData falls back to records when no samples")
    void getFlameGraphDataFallsBackToRecords() {
        collector.addRecord(createRecord("com.example.A", "m", 2_000_000L));

        Map<String, Object> data = collector.getFlameGraphData();
        assertEquals("root", data.get("name"));
        // Should have record-based value (duration in micros)
        assertTrue(((long) data.get("value")) > 0);
    }

    @Test
    @DisplayName("clear removes all data")
    void clearRemovesAll() {
        collector.addRecord(createRecord("com.example.A", "m", 1000L));
        collector.addStackSample(new StackTraceElement[]{
                new StackTraceElement("com.example.X", "m", "X.java", 1)
        });

        collector.clear();

        assertEquals(0, collector.getRecordCount());
        assertEquals(0, collector.getSampleCount());
    }

    private TraceRecord createRecord(String className, String methodName, long durationNanos) {
        return new TraceRecord(
                "id", className, methodName,
                List.of(), List.of(),
                null, null, null, durationNanos,
                "main", 1L, Instant.now(), 0
        );
    }
}
