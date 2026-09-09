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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a fully qualified class name to a single loaded {@link Class},
 * optionally disambiguated by ClassLoader identity hash ({@code classLoaderId}).
 *
 * <p>API compatibility:
 * <ul>
 *   <li>Unique match (no {@code classLoaderId}) → success</li>
 *   <li>Multiple matches without {@code classLoaderId} → {@link AmbiguousClassException}</li>
 *   <li>With {@code classLoaderId} → exact loader match or not found</li>
 * </ul>
 */
public final class LoadedClassResolver {

    private LoadedClassResolver() {
    }

    /**
     * Finds all loaded classes with the given name.
     *
     * @param className fully qualified class name
     * @return matching classes (may be empty)
     */
    public static List<Class<?>> findAll(String className) {
        if (className == null || className.isBlank()) {
            return List.of();
        }
        Instrumentation inst = InstrumentationHolder.get();
        List<Class<?>> matches = new ArrayList<>();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                matches.add(clazz);
            }
        }
        return matches;
    }

    /**
     * Resolves a single loaded class.
     *
     * @param className     fully qualified class name
     * @param classLoaderId optional loader id ({@link ClassLoaderService#getLoaderId}), or null
     * @return the resolved class, or {@code null} if not found
     * @throws AmbiguousClassException if multiple loaders match and no id was given
     */
    public static Class<?> resolve(String className, String classLoaderId) {
        List<Class<?>> matches = findAll(className);
        if (matches.isEmpty()) {
            return null;
        }

        if (classLoaderId != null && !classLoaderId.isBlank()) {
            for (Class<?> clazz : matches) {
                if (classLoaderId.equals(ClassLoaderService.getLoaderId(clazz.getClassLoader()))) {
                    return clazz;
                }
            }
            return null;
        }

        if (matches.size() == 1) {
            return matches.get(0);
        }

        throw new AmbiguousClassException(className, toCandidates(matches));
    }

    /**
     * Builds candidate maps for HTTP 409 responses.
     */
    public static List<Map<String, Object>> toCandidates(List<Class<?>> classes) {
        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Class<?> clazz : classes) {
            Map<String, Object> c = new LinkedHashMap<>();
            ClassLoader cl = clazz.getClassLoader();
            c.put("className", clazz.getName());
            c.put("classLoaderId", ClassLoaderService.getLoaderId(cl));
            c.put("classLoader", cl != null ? cl.toString() : "bootstrap");
            candidates.add(c);
        }
        return candidates;
    }
}
