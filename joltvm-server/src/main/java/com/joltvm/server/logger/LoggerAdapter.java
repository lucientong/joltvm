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

package com.joltvm.server.logger;

import java.util.List;
import java.util.Map;

/**
 * Abstraction over different logging frameworks (Logback, Log4j2, JUL).
 *
 * <p>Each adapter is discovered at runtime via reflection — zero compile-time
 * dependency on any specific logging framework.
 */
public interface LoggerAdapter {

    /**
     * Returns the name of the logging framework.
     */
    String frameworkName();

    /**
     * Checks if this logging framework is available in the current classloader.
     */
    boolean isAvailable();

    /**
     * Lists all known loggers with their current levels.
     *
     * @return list of maps, each with "name", "level", "effectiveLevel"
     */
    List<Map<String, Object>> listLoggers();

    /**
     * Changes the level of the specified logger.
     *
     * @param loggerName the logger name
     * @param level      the target level (e.g., "DEBUG", "INFO", "WARN", "ERROR")
     * @return map with "previousLevel" and "newLevel"
     * @throws IllegalArgumentException if the logger or level is invalid
     */
    Map<String, String> setLevel(String loggerName, String level);
}
