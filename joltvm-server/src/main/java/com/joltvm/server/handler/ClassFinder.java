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

package com.joltvm.server.handler;

import com.joltvm.server.classloader.AmbiguousClassException;
import com.joltvm.server.classloader.LoadedClassResolver;

/**
 * Utility class for finding loaded classes via the Instrumentation API.
 *
 * <p>Used by {@link ClassDetailHandler}, {@link ClassSourceHandler}, and hot-swap
 * paths to locate a class by FQCN, optionally disambiguated by ClassLoader id.
 */
final class ClassFinder {

    private ClassFinder() {
        // Utility class — no instantiation
    }

    /**
     * Finds a loaded class by its fully qualified name.
     *
     * <p>If multiple ClassLoaders define the same name, throws
     * {@link AmbiguousClassException} — callers should return HTTP 409.
     *
     * @param className the fully qualified class name (e.g., {@code java.lang.String})
     * @return the class, or {@code null} if not found among loaded classes
     * @throws AmbiguousClassException if multiple loaders match
     */
    static Class<?> findClass(String className) {
        return findClass(className, null);
    }

    /**
     * Finds a loaded class by name and optional ClassLoader id.
     *
     * @param className     fully qualified class name
     * @param classLoaderId optional loader identity hash, or null for unique-match mode
     * @return the class, or {@code null} if not found
     * @throws AmbiguousClassException if multiple loaders match and no id was given
     */
    static Class<?> findClass(String className, String classLoaderId) {
        return LoadedClassResolver.resolve(className, classLoaderId);
    }
}
