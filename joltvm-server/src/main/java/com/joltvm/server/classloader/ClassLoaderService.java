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

package com.joltvm.server.classloader;

import com.joltvm.agent.InstrumentationHolder;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for ClassLoader analysis: tree structure, loaded class lists,
 * and class conflict detection (same FQCN loaded by multiple ClassLoaders).
 *
 * <p>Uses {@link Instrumentation#getAllLoadedClasses()} to enumerate all classes
 * in the JVM, then groups them by their defining ClassLoader.
 */
public class ClassLoaderService {

    private static final Logger LOG = Logger.getLogger(ClassLoaderService.class.getName());

    /** Maximum number of classes returned per ClassLoader to prevent response blow-up. */
    private static final int MAX_CLASSES_PER_LOADER = 5000;

    /**
     * Returns the ClassLoader hierarchy as a tree structure.
     *
     * <p>Each node contains: id (identity hash), name, class count, parent info,
     * and children list. The bootstrap ClassLoader (null) is represented as a
     * synthetic root node with id "bootstrap".
     *
     * @return map with "tree" (root nodes) and "totalLoaders" count
     */
    public Map<String, Object> getClassLoaderTree() {
        Map<ClassLoader, List<Class<?>>> loaderToClasses = groupClassesByLoader();

        // Build node map (identity-based to handle ClassLoader equality correctly)
        Map<ClassLoader, Map<String, Object>> nodeMap = new IdentityHashMap<>();
        Map<String, Object> bootstrapNode = createLoaderNode(null, loaderToClasses.getOrDefault(null, List.of()));
        nodeMap.put(null, bootstrapNode);

        for (Map.Entry<ClassLoader, List<Class<?>>> entry : loaderToClasses.entrySet()) {
            ClassLoader cl = entry.getKey();
            if (cl != null) {
                nodeMap.put(cl, createLoaderNode(cl, entry.getValue()));
            }
        }

        // Also include loaders that have no directly loaded classes but are parents
        Set<ClassLoader> allLoaders = Collections.newSetFromMap(new IdentityHashMap<>());
        allLoaders.addAll(nodeMap.keySet());
        for (ClassLoader cl : new ArrayList<>(allLoaders)) {
            ClassLoader parent = getParentSafe(cl);
            while (parent != null && !allLoaders.contains(parent)) {
                allLoaders.add(parent);
                nodeMap.put(parent, createLoaderNode(parent, loaderToClasses.getOrDefault(parent, List.of())));
                parent = getParentSafe(parent);
            }
        }

        // Wire parent-child relationships
        for (Map.Entry<ClassLoader, Map<String, Object>> entry : nodeMap.entrySet()) {
            ClassLoader cl = entry.getKey();
            if (cl == null) continue;

            ClassLoader parent = getParentSafe(cl);
            Map<String, Object> parentNode = parent != null ? nodeMap.get(parent) : bootstrapNode;
            if (parentNode != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> children = (List<Map<String, Object>>) parentNode.get("children");
                children.add(entry.getValue());
            }
        }

        // Sort children by class count descending
        sortChildrenRecursive(bootstrapNode);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tree", List.of(bootstrapNode));
        result.put("totalLoaders", nodeMap.size());
        return result;
    }

    /**
     * Returns classes loaded by a specific ClassLoader, identified by its identity hash code.
     *
     * @param loaderIdStr the identity hash code string, or "bootstrap" for the bootstrap loader
     * @param page        0-based page number
     * @param size        page size (max {@value MAX_CLASSES_PER_LOADER})
     * @param search      optional class name filter (case-insensitive contains)
     * @return map with "classes" list, "count", "page", "size", "totalPages"
     */
    public Map<String, Object> getClassesByLoader(String loaderIdStr, int page, int size, String search) {
        if (page < 0) page = 0;
        size = Math.min(size, MAX_CLASSES_PER_LOADER);
        Map<ClassLoader, List<Class<?>>> loaderToClasses = groupClassesByLoader();

        ClassLoader targetLoader = findLoaderById(loaderIdStr, loaderToClasses.keySet());
        List<Class<?>> classes;
        if ("bootstrap".equals(loaderIdStr)) {
            classes = loaderToClasses.getOrDefault(null, List.of());
        } else if (targetLoader != null) {
            classes = loaderToClasses.getOrDefault(targetLoader, List.of());
        } else {
            classes = List.of();
        }

        // Filter by search
        List<String> classNames = new ArrayList<>();
        for (Class<?> cls : classes) {
            String name = cls.getName();
            if (search == null || search.isEmpty() || name.toLowerCase().contains(search.toLowerCase())) {
                classNames.add(name);
            }
        }
        Collections.sort(classNames);

        int totalCount = classNames.size();
        int totalPages = (totalCount + size - 1) / size;
        int fromIndex = Math.min(page * size, totalCount);
        int toIndex = Math.min(fromIndex + size, totalCount);
        List<String> pageClasses = classNames.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("loaderId", loaderIdStr);
        result.put("classes", pageClasses);
        result.put("count", pageClasses.size());
        result.put("totalCount", totalCount);
        result.put("page", page);
        result.put("size", size);
        result.put("totalPages", totalPages);
        return result;
    }

    /**
     * Detects class conflicts — same fully qualified class name loaded by multiple ClassLoaders.
     *
     * @return map with "conflicts" list (each with className, loaders) and "count"
     */
    public Map<String, Object> detectConflicts() {
        Map<ClassLoader, List<Class<?>>> loaderToClasses = groupClassesByLoader();

        // className -> set of loader IDs
        Map<String, List<String>> classToLoaders = new HashMap<>();
        for (Map.Entry<ClassLoader, List<Class<?>>> entry : loaderToClasses.entrySet()) {
            String loaderId = getLoaderId(entry.getKey());
            for (Class<?> cls : entry.getValue()) {
                classToLoaders.computeIfAbsent(cls.getName(), k -> new ArrayList<>()).add(loaderId);
            }
        }

        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> entry : classToLoaders.entrySet()) {
            if (entry.getValue().size() > 1) {
                Map<String, Object> conflict = new LinkedHashMap<>();
                conflict.put("className", entry.getKey());
                conflict.put("loaders", entry.getValue());
                conflict.put("count", entry.getValue().size());
                conflicts.add(conflict);
            }
        }
        conflicts.sort(Comparator.comparing(m -> (String) m.get("className")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conflicts", conflicts);
        result.put("count", conflicts.size());
        return result;
    }

    // ---- internal helpers ----

    private Map<ClassLoader, List<Class<?>>> groupClassesByLoader() {
        // identity-based map to correctly handle ClassLoader instances
        Map<ClassLoader, List<Class<?>>> map = new IdentityHashMap<>();
        try {
            Instrumentation inst = InstrumentationHolder.get();
            Class<?>[] allClasses = inst.getAllLoadedClasses();
            for (Class<?> cls : allClasses) {
                ClassLoader cl = cls.getClassLoader(); // null = bootstrap
                map.computeIfAbsent(cl, k -> new ArrayList<>()).add(cls);
            }
        } catch (IllegalStateException e) {
            LOG.log(Level.WARNING, "Instrumentation not available for ClassLoader analysis", e);
        }
        return map;
    }

    private Map<String, Object> createLoaderNode(ClassLoader cl, List<Class<?>> classes) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", getLoaderId(cl));
        node.put("name", getLoaderName(cl));
        node.put("className", cl != null ? cl.getClass().getName() : "bootstrap");
        node.put("classCount", classes.size());
        node.put("children", new ArrayList<Map<String, Object>>());
        if (cl != null) {
            ClassLoader parent = getParentSafe(cl);
            node.put("parentId", getLoaderId(parent));
        }
        return node;
    }

    static String getLoaderId(ClassLoader cl) {
        return cl == null ? "bootstrap" : String.valueOf(System.identityHashCode(cl));
    }

    private String getLoaderName(ClassLoader cl) {
        if (cl == null) return "Bootstrap ClassLoader";
        // Java 9+ getName()
        try {
            java.lang.reflect.Method getName = ClassLoader.class.getMethod("getName");
            Object name = getName.invoke(cl);
            if (name != null) return name.toString();
        } catch (Exception ignored) {
            // pre-Java 9 or access error
        }
        String str = cl.toString();
        return str.length() > 120 ? str.substring(0, 120) + "..." : str;
    }

    private ClassLoader getParentSafe(ClassLoader cl) {
        if (cl == null) return null;
        try {
            return cl.getParent();
        } catch (SecurityException e) {
            return null;
        }
    }

    private ClassLoader findLoaderById(String idStr, Set<ClassLoader> loaders) {
        if ("bootstrap".equals(idStr)) return null;
        try {
            int targetHash = Integer.parseInt(idStr);
            for (ClassLoader cl : loaders) {
                if (cl != null && System.identityHashCode(cl) == targetHash) {
                    return cl;
                }
            }
        } catch (NumberFormatException ignored) {
            // invalid id
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void sortChildrenRecursive(Map<String, Object> node) {
        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        children.sort(Comparator.<Map<String, Object>, Integer>comparing(m -> (Integer) m.get("classCount")).reversed());
        for (Map<String, Object> child : children) {
            sortChildrenRecursive(child);
        }
    }
}
