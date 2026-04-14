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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

/**
 * Collects method call stack data and aggregates it into flame graph format.
 *
 * <p>This collector receives trace records from the Byte Buddy Advice and:
 * <ol>
 *   <li>Maintains a ring buffer of recent trace records (call chain log)</li>
 *   <li>Builds an aggregated flame graph tree from captured stack samples</li>
 * </ol>
 *
 * <p>The flame graph data is compatible with the d3-flame-graph JSON format:
 * <pre>
 * {
 *   "name": "root",
 *   "value": 100,
 *   "children": [
 *     {
 *       "name": "com.example.Service#handleRequest",
 *       "value": 80,
 *       "children": [ ... ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * <p>Thread-safe: all public methods are synchronized or use concurrent collections.
 */
public class FlameGraphCollector {

    private static final Logger LOG = Logger.getLogger(FlameGraphCollector.class.getName());

    /** Maximum number of trace records kept in memory. */
    static final int MAX_RECORDS = 500;

    /** Maximum number of stack samples kept for flame graph generation. */
    static final int MAX_SAMPLES = 1000;

    private final List<TraceRecord> records = new CopyOnWriteArrayList<>();
    private final List<StackTraceElement[]> stackSamples = new CopyOnWriteArrayList<>();

    /**
     * Adds a trace record (method invocation) to the collector.
     *
     * @param record the trace record to add
     */
    public void addRecord(TraceRecord record) {
        records.add(record);
        // Trim old entries if exceeds max
        while (records.size() > MAX_RECORDS) {
            records.remove(0);
        }
    }

    /**
     * Adds a stack trace sample for flame graph aggregation.
     *
     * @param stackTrace the stack trace elements (from Thread.getStackTrace())
     */
    public void addStackSample(StackTraceElement[] stackTrace) {
        stackSamples.add(stackTrace);
        while (stackSamples.size() > MAX_SAMPLES) {
            stackSamples.remove(0);
        }
    }

    /**
     * Returns a snapshot of recent trace records (newest first).
     *
     * @return unmodifiable list of trace records
     */
    public List<TraceRecord> getRecords() {
        List<TraceRecord> snapshot = new ArrayList<>(records);
        Collections.reverse(snapshot);
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Returns a snapshot of recent trace records (newest first), limited to the specified count.
     *
     * @param limit maximum number of records to return
     * @return unmodifiable list of trace records
     */
    public List<TraceRecord> getRecords(int limit) {
        List<TraceRecord> all = getRecords();
        if (limit >= all.size()) {
            return all;
        }
        return all.subList(0, limit);
    }

    /**
     * Returns the total number of records currently held.
     *
     * @return the record count
     */
    public int getRecordCount() {
        return records.size();
    }

    /**
     * Returns the total number of stack samples currently held.
     *
     * @return the sample count
     */
    public int getSampleCount() {
        return stackSamples.size();
    }

    /**
     * Builds a flame graph tree from the collected trace records.
     *
     * <p>Uses the trace records to build a call tree where each node
     * represents a method frame and the value is the total duration
     * in microseconds.
     *
     * @return the root node of the flame graph tree, serializable to d3-flame-graph JSON
     */
    public FlameGraphNode buildFlameGraphFromRecords() {
        FlameGraphNode root = new FlameGraphNode("root", 0);

        for (TraceRecord record : records) {
            String frameName = record.className() + "#" + record.methodName();
            long durationMicros = record.durationNanos() / 1_000;

            FlameGraphNode child = root.getOrCreateChild(frameName);
            child.addValue(durationMicros);
            root.addValue(durationMicros);
        }

        return root;
    }

    /**
     * Builds a flame graph tree from the collected stack samples.
     *
     * <p>Each stack sample contributes one "sample unit" to each frame
     * in the stack. Stacks are reversed so the root frame appears at the top.
     *
     * @return the root node of the flame graph tree
     */
    public FlameGraphNode buildFlameGraphFromSamples() {
        FlameGraphNode root = new FlameGraphNode("root", 0);

        for (StackTraceElement[] stack : stackSamples) {
            root.addValue(1);
            FlameGraphNode current = root;

            // Traverse from bottom (main) to top (leaf) of the stack
            for (int i = stack.length - 1; i >= 0; i--) {
                StackTraceElement frame = stack[i];
                String frameName = frame.getClassName() + "#" + frame.getMethodName();
                FlameGraphNode child = current.getOrCreateChild(frameName);
                child.addValue(1);
                current = child;
            }
        }

        return root;
    }

    /**
     * Returns the flame graph data as a d3-flame-graph compatible map.
     *
     * <p>If stack samples are available, uses sample-based aggregation.
     * Otherwise, falls back to trace-record-based aggregation.
     *
     * @return the flame graph data as a nested map
     */
    public Map<String, Object> getFlameGraphData() {
        if (!stackSamples.isEmpty()) {
            return buildFlameGraphFromSamples().toMap();
        }
        return buildFlameGraphFromRecords().toMap();
    }

    /**
     * Returns the flame graph data for a specific view type.
     *
     * <p>Supported view types:
     * <ul>
     *   <li>{@code "cpu"} — stack-sample-based aggregation (CPU time perspective)</li>
     *   <li>{@code "wall"} — trace-record-based aggregation (wall-clock duration)</li>
     * </ul>
     * Falls back to {@link #getFlameGraphData()} for unknown view types.
     *
     * @param view the view type ("cpu" or "wall")
     * @return the flame graph data as a nested map
     */
    public Map<String, Object> getFlameGraphData(String view) {
        if ("cpu".equalsIgnoreCase(view)) {
            return buildFlameGraphFromSamples().toMap();
        }
        if ("wall".equalsIgnoreCase(view)) {
            return buildFlameGraphFromRecords().toMap();
        }
        return getFlameGraphData();
    }

    /**
     * Clears all collected records and samples.
     */
    public void clear() {
        records.clear();
        stackSamples.clear();
        LOG.info("Flame graph collector cleared");
    }
}
