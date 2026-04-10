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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A node in the flame graph tree, compatible with the d3-flame-graph JSON format.
 *
 * <p>Each node represents a method frame in the aggregated call stack:
 * <pre>
 * {
 *   "name": "com.example.MyClass#doWork",
 *   "value": 42,         // total samples or total duration (microseconds)
 *   "children": [ ... ]
 * }
 * </pre>
 *
 * <p>Thread-safe: this class is not thread-safe; external synchronization is required
 * when building the tree from multiple threads.
 */
public final class FlameGraphNode {

    private final String name;
    private long value;
    private final List<FlameGraphNode> children;

    /**
     * Creates a new flame graph node.
     *
     * @param name  the frame label (e.g., "com.example.MyClass#doWork")
     * @param value the frame value (e.g., total duration in microseconds or sample count)
     */
    public FlameGraphNode(String name, long value) {
        this.name = Objects.requireNonNull(name, "name");
        this.value = value;
        this.children = new ArrayList<>();
    }

    /**
     * Returns the frame label.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the frame value (total duration or sample count).
     *
     * @return the value
     */
    public long getValue() {
        return value;
    }

    /**
     * Adds to the frame value.
     *
     * @param delta the amount to add
     */
    public void addValue(long delta) {
        this.value += delta;
    }

    /**
     * Returns the children list (mutable for tree building).
     *
     * @return the children
     */
    public List<FlameGraphNode> getChildren() {
        return children;
    }

    /**
     * Finds a child by name, or creates and adds a new child if not found.
     *
     * @param childName the child frame name
     * @return the existing or newly created child
     */
    public FlameGraphNode getOrCreateChild(String childName) {
        for (FlameGraphNode child : children) {
            if (child.name.equals(childName)) {
                return child;
            }
        }
        FlameGraphNode child = new FlameGraphNode(childName, 0);
        children.add(child);
        return child;
    }

    /**
     * Converts this node tree to a d3-flame-graph-compatible map structure.
     *
     * @return a nested map of {name, value, children}
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", name);
        map.put("value", value);
        if (!children.isEmpty()) {
            List<Map<String, Object>> childMaps = new ArrayList<>();
            for (FlameGraphNode child : children) {
                childMaps.add(child.toMap());
            }
            map.put("children", childMaps);
        } else {
            map.put("children", Collections.emptyList());
        }
        return map;
    }
}
