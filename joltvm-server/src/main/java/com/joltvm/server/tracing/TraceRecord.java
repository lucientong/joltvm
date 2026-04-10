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

import java.time.Instant;
import java.util.List;

/**
 * Immutable record of a single method invocation captured during tracing.
 *
 * <p>Each record captures the method signature, arguments, return value,
 * execution time, thread information, and whether an exception was thrown.
 *
 * @param id            unique ID for this trace record
 * @param className     the fully qualified class name
 * @param methodName    the method name
 * @param parameterTypes list of parameter type names
 * @param arguments     string representations of the arguments (may be truncated)
 * @param returnValue   string representation of the return value (null if void or exception)
 * @param exceptionType exception type thrown (null if no exception)
 * @param exceptionMessage exception message (null if no exception)
 * @param durationNanos execution time in nanoseconds
 * @param threadName    the name of the thread that executed the method
 * @param threadId      the ID of the thread that executed the method
 * @param timestamp     when the invocation started
 * @param depth         call depth within a trace session (0 = root call)
 */
public record TraceRecord(
        String id,
        String className,
        String methodName,
        List<String> parameterTypes,
        List<String> arguments,
        String returnValue,
        String exceptionType,
        String exceptionMessage,
        long durationNanos,
        String threadName,
        long threadId,
        Instant timestamp,
        int depth
) {

    /**
     * Returns the duration in milliseconds (with decimal precision).
     *
     * @return duration in ms
     */
    public double durationMs() {
        return durationNanos / 1_000_000.0;
    }

    /**
     * Returns whether the method call threw an exception.
     *
     * @return true if an exception was thrown
     */
    public boolean hasException() {
        return exceptionType != null;
    }

    /**
     * Returns the full method signature (e.g., "com.example.MyClass#doWork(String, int)").
     *
     * @return the method signature string
     */
    public String signature() {
        return className + "#" + methodName + "(" + String.join(", ", parameterTypes) + ")";
    }
}
