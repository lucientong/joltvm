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

import java.util.List;
import java.util.Map;

/**
 * Thrown when a class name matches multiple loaded ClassLoaders and no
 * {@code classLoaderId} was provided to disambiguate.
 */
public class AmbiguousClassException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String className;
    private final List<Map<String, Object>> candidates;

    public AmbiguousClassException(String className, List<Map<String, Object>> candidates) {
        super("Multiple ClassLoaders loaded '" + className + "'. Specify classLoaderId to disambiguate.");
        this.className = className;
        this.candidates = List.copyOf(candidates);
    }

    public String getClassName() {
        return className;
    }

    /**
     * Candidate loaders, each with {@code classLoaderId}, {@code classLoader}, and {@code className}.
     */
    public List<Map<String, Object>> getCandidates() {
        return candidates;
    }
}
