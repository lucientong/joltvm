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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ResultSerializerTest {

    @Test
    void serializesNull() {
        assertNull(ResultSerializer.serialize(null, 5));
    }

    @Test
    void serializesString() {
        assertEquals("hello", ResultSerializer.serialize("hello", 5));
    }

    @Test
    void serializesNumber() {
        assertEquals(42, ResultSerializer.serialize(42, 5));
        assertEquals(3.14, ResultSerializer.serialize(3.14, 5));
    }

    @Test
    void serializesBoolean() {
        assertEquals(true, ResultSerializer.serialize(true, 5));
    }

    @Test
    void serializesEnum() {
        assertEquals("RUNNABLE", ResultSerializer.serialize(Thread.State.RUNNABLE, 5));
    }

    @Test
    void serializesClass() {
        assertEquals("java.lang.String", ResultSerializer.serialize(String.class, 5));
    }

    @Test
    void serializesCharacter() {
        assertEquals("A", ResultSerializer.serialize('A', 5));
    }

    @Test
    void serializesArray() {
        Object result = ResultSerializer.serialize(new int[]{1, 2, 3}, 5);
        assertInstanceOf(List.class, result);
        assertEquals(3, ((List<?>) result).size());
    }

    @Test
    void serializesList() {
        Object result = ResultSerializer.serialize(List.of("a", "b"), 5);
        assertInstanceOf(List.class, result);
        assertEquals(2, ((List<?>) result).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void serializesMap() {
        Object result = ResultSerializer.serialize(Map.of("key", "value"), 5);
        assertInstanceOf(Map.class, result);
        assertEquals("value", ((Map<String, Object>) result).get("key"));
    }

    @Test
    void respectsDepthLimit() {
        Object result = ResultSerializer.serialize(Map.of("a", Map.of("b", "c")), 1);
        assertInstanceOf(Map.class, result);
        // At depth 1, nested map should be truncated
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) result;
        assertNotNull(map.get("a"));
    }

    @Test
    void handlesCircularReference() {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("self", map); // circular!
        Object result = ResultSerializer.serialize(map, 5);
        assertNotNull(result);
    }

    @Test
    void handlesObjectWithToString() {
        Object obj = new Object() {
            @Override public String toString() { return "custom-object"; }
        };
        Object result = ResultSerializer.serialize(obj, 5);
        assertNotNull(result);
    }
}
