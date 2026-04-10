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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FlameGraphNode}.
 */
@DisplayName("FlameGraphNode")
class FlameGraphNodeTest {

    @Test
    @DisplayName("constructor sets name and value")
    void constructorSetsFields() {
        FlameGraphNode node = new FlameGraphNode("root", 100);
        assertEquals("root", node.getName());
        assertEquals(100, node.getValue());
        assertTrue(node.getChildren().isEmpty());
    }

    @Test
    @DisplayName("constructor rejects null name")
    void constructorRejectsNullName() {
        assertThrows(NullPointerException.class, () -> new FlameGraphNode(null, 0));
    }

    @Test
    @DisplayName("addValue accumulates")
    void addValueAccumulates() {
        FlameGraphNode node = new FlameGraphNode("test", 10);
        node.addValue(5);
        assertEquals(15, node.getValue());
        node.addValue(20);
        assertEquals(35, node.getValue());
    }

    @Test
    @DisplayName("getOrCreateChild creates new child")
    void getOrCreateChildCreatesNew() {
        FlameGraphNode root = new FlameGraphNode("root", 0);
        FlameGraphNode child = root.getOrCreateChild("child1");

        assertNotNull(child);
        assertEquals("child1", child.getName());
        assertEquals(0, child.getValue());
        assertEquals(1, root.getChildren().size());
    }

    @Test
    @DisplayName("getOrCreateChild returns existing child")
    void getOrCreateChildReturnsExisting() {
        FlameGraphNode root = new FlameGraphNode("root", 0);
        FlameGraphNode child1 = root.getOrCreateChild("child1");
        child1.addValue(10);

        FlameGraphNode child1Again = root.getOrCreateChild("child1");
        assertSame(child1, child1Again);
        assertEquals(10, child1Again.getValue());
        assertEquals(1, root.getChildren().size());
    }

    @Test
    @DisplayName("getOrCreateChild supports multiple children")
    void getOrCreateChildMultipleChildren() {
        FlameGraphNode root = new FlameGraphNode("root", 0);
        root.getOrCreateChild("child1");
        root.getOrCreateChild("child2");
        root.getOrCreateChild("child3");

        assertEquals(3, root.getChildren().size());
    }

    @Test
    @DisplayName("toMap produces d3-flame-graph compatible structure")
    void toMapProducesCorrectStructure() {
        FlameGraphNode root = new FlameGraphNode("root", 100);
        FlameGraphNode child1 = root.getOrCreateChild("A#method1");
        child1.addValue(60);
        FlameGraphNode child2 = root.getOrCreateChild("B#method2");
        child2.addValue(40);

        Map<String, Object> map = root.toMap();

        assertEquals("root", map.get("name"));
        assertEquals(100L, map.get("value"));
        assertTrue(map.containsKey("children"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) map.get("children");
        assertEquals(2, children.size());
        assertEquals("A#method1", children.get(0).get("name"));
        assertEquals(60L, children.get(0).get("value"));
        assertEquals("B#method2", children.get(1).get("name"));
        assertEquals(40L, children.get(1).get("value"));
    }

    @Test
    @DisplayName("toMap includes empty children list for leaf nodes")
    void toMapEmptyChildrenForLeaf() {
        FlameGraphNode leaf = new FlameGraphNode("leaf", 5);
        Map<String, Object> map = leaf.toMap();

        assertTrue(map.containsKey("children"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) map.get("children");
        assertTrue(children.isEmpty());
    }

    @Test
    @DisplayName("toMap produces nested tree")
    void toMapNestedTree() {
        FlameGraphNode root = new FlameGraphNode("root", 10);
        FlameGraphNode child = root.getOrCreateChild("parent");
        child.addValue(8);
        FlameGraphNode grandchild = child.getOrCreateChild("leaf");
        grandchild.addValue(5);

        Map<String, Object> map = root.toMap();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> children = (List<Map<String, Object>>) map.get("children");
        assertEquals(1, children.size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grandchildren =
                (List<Map<String, Object>>) children.get(0).get("children");
        assertEquals(1, grandchildren.size());
        assertEquals("leaf", grandchildren.get(0).get("name"));
        assertEquals(5L, grandchildren.get(0).get("value"));
    }
}
