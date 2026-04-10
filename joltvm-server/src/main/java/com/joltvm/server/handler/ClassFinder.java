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

import com.joltvm.agent.InstrumentationHolder;

import java.lang.instrument.Instrumentation;

/**
 * Utility class for finding loaded classes via the Instrumentation API.
 *
 * <p>Used by {@link ClassDetailHandler} and {@link ClassSourceHandler} to
 * locate a class by its fully qualified name among all loaded classes.
 */
final class ClassFinder {

    private ClassFinder() {
        // Utility class — no instantiation
    }

    /**
     * Finds a loaded class by its fully qualified name.
     *
     * @param className the fully qualified class name (e.g., {@code java.lang.String})
     * @return the class, or {@code null} if not found among loaded classes
     */
    static Class<?> findClass(String className) {
        Instrumentation inst = InstrumentationHolder.get();
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }
}
