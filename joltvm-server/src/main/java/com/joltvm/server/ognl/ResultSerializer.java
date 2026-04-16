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

package com.joltvm.server.ognl;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Safe serialization of OGNL evaluation results to JSON-compatible structures.
 *
 * <p>Handles:
 * <ul>
 *   <li>Circular reference detection (identity-based)</li>
 *   <li>Depth limiting to prevent stack overflow</li>
 *   <li>Collection size limiting to prevent memory exhaustion</li>
 *   <li>Graceful degradation for non-serializable objects</li>
 * </ul>
 */
public class ResultSerializer {

    /** Default maximum depth for nested object serialization. */
    public static final int DEFAULT_MAX_DEPTH = 5;

    /** Maximum collection/array elements to serialize. */
    private static final int MAX_COLLECTION_SIZE = 200;

    private ResultSerializer() {
        // Utility class
    }

    /**
     * Serializes an OGNL result to a JSON-safe structure.
     *
     * @param value    the value to serialize
     * @param maxDepth maximum nesting depth
     * @return a JSON-compatible object (Map, List, String, Number, Boolean, null)
     */
    public static Object serialize(Object value, int maxDepth) {
        Set<Object> visited = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        return serializeInternal(value, 0, maxDepth, visited);
    }

    private static Object serializeInternal(Object value, int depth, int maxDepth, Set<Object> visited) {
        if (value == null) {
            return null;
        }

        // Primitives and simple types — always safe
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }

        if (value instanceof Character) {
            return value.toString();
        }

        if (value instanceof Enum<?> e) {
            return e.name();
        }

        if (value instanceof Class<?> cls) {
            return cls.getName();
        }

        // Depth limit
        if (depth >= maxDepth) {
            return truncated(value);
        }

        // Circular reference detection
        if (!visited.add(value)) {
            return "[circular reference: " + value.getClass().getSimpleName() + "]";
        }

        try {
            // Arrays
            if (value.getClass().isArray()) {
                return serializeArray(value, depth, maxDepth, visited);
            }

            // Maps
            if (value instanceof Map<?, ?> map) {
                return serializeMap(map, depth, maxDepth, visited);
            }

            // Collections
            if (value instanceof Collection<?> coll) {
                return serializeCollection(coll, depth, maxDepth, visited);
            }

            // Other objects — convert to map of fields via toString
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("@type", value.getClass().getName());
            result.put("@toString", safeToString(value));
            return result;
        } finally {
            visited.remove(value);
        }
    }

    private static List<Object> serializeArray(Object array, int depth, int maxDepth, Set<Object> visited) {
        int length = Array.getLength(array);
        int limit = Math.min(length, MAX_COLLECTION_SIZE);
        List<Object> result = new ArrayList<>(limit + 1);
        for (int i = 0; i < limit; i++) {
            result.add(serializeInternal(Array.get(array, i), depth + 1, maxDepth, visited));
        }
        if (length > limit) {
            result.add("... (" + (length - limit) + " more elements)");
        }
        return result;
    }

    private static Map<String, Object> serializeMap(Map<?, ?> map, int depth, int maxDepth, Set<Object> visited) {
        Map<String, Object> result = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (count >= MAX_COLLECTION_SIZE) {
                result.put("...", "(" + (map.size() - count) + " more entries)");
                break;
            }
            String key = entry.getKey() != null ? entry.getKey().toString() : "null";
            result.put(key, serializeInternal(entry.getValue(), depth + 1, maxDepth, visited));
            count++;
        }
        return result;
    }

    private static List<Object> serializeCollection(Collection<?> coll, int depth, int maxDepth, Set<Object> visited) {
        int limit = Math.min(coll.size(), MAX_COLLECTION_SIZE);
        List<Object> result = new ArrayList<>(limit + 1);
        int count = 0;
        for (Object item : coll) {
            if (count >= limit) {
                result.add("... (" + (coll.size() - limit) + " more elements)");
                break;
            }
            result.add(serializeInternal(item, depth + 1, maxDepth, visited));
            count++;
        }
        return result;
    }

    private static String truncated(Object value) {
        return "[" + value.getClass().getSimpleName() + ": depth limit reached]";
    }

    private static String safeToString(Object value) {
        try {
            String str = value.toString();
            return str.length() > 1000 ? str.substring(0, 1000) + "..." : str;
        } catch (Exception e) {
            return "[toString() failed: " + e.getClass().getSimpleName() + "]";
        }
    }
}
