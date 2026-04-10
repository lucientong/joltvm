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

package com.joltvm.server.compile;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Result of an in-memory Java compilation.
 *
 * @param success     whether the compilation succeeded
 * @param bytecodeMap map of fully qualified class name to bytecode (empty on failure)
 * @param diagnostics list of compiler diagnostic messages
 */
public record CompileResult(
        boolean success,
        Map<String, byte[]> bytecodeMap,
        List<String> diagnostics
) {
    /**
     * Creates a successful result.
     */
    public static CompileResult success(Map<String, byte[]> bytecodeMap) {
        return new CompileResult(true, bytecodeMap, List.of());
    }

    /**
     * Creates a failed result with diagnostics.
     */
    public static CompileResult failure(List<String> diagnostics) {
        return new CompileResult(false, Collections.emptyMap(), diagnostics);
    }
}
