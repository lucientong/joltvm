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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Service for dynamic logger level management.
 *
 * <p>Auto-detects the logging framework in use (priority: Logback &gt; Log4j2 &gt; JUL)
 * and delegates all operations to the appropriate {@link LoggerAdapter}.
 */
public class LoggerService {

    private static final Logger LOG = Logger.getLogger(LoggerService.class.getName());

    private final LoggerAdapter adapter;

    /**
     * Creates a LoggerService that auto-detects the logging framework.
     */
    public LoggerService() {
        this.adapter = detectAdapter();
        LOG.info("Logger framework detected: " + adapter.frameworkName());
    }

    /**
     * Creates a LoggerService with an explicit adapter (for testing).
     */
    public LoggerService(LoggerAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Returns the active logging framework name and all loggers.
     *
     * @return map with "framework", "loggers", "count"
     */
    public Map<String, Object> listLoggers() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework", adapter.frameworkName());
        List<Map<String, Object>> loggers = adapter.listLoggers();
        result.put("loggers", loggers);
        result.put("count", loggers.size());
        return result;
    }

    /**
     * Changes the level of a logger.
     *
     * @param loggerName the logger name
     * @param level      the target level
     * @return map with "framework", "loggerName", "previousLevel", "newLevel"
     */
    public Map<String, Object> setLevel(String loggerName, String level) {
        Map<String, String> changeResult = adapter.setLevel(loggerName, level);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("framework", adapter.frameworkName());
        result.put("loggerName", loggerName);
        result.put("previousLevel", changeResult.get("previousLevel"));
        result.put("newLevel", changeResult.get("newLevel"));
        return result;
    }

    /**
     * Returns the detected adapter (for testing).
     */
    LoggerAdapter getAdapter() {
        return adapter;
    }

    /**
     * Detects the logging framework. Priority: Logback &gt; Log4j2 &gt; JUL.
     */
    static LoggerAdapter detectAdapter() {
        LogbackAdapter logback = new LogbackAdapter();
        if (logback.isAvailable()) return logback;

        Log4j2Adapter log4j2 = new Log4j2Adapter();
        if (log4j2.isAvailable()) return log4j2;

        return new JulAdapter();
    }
}
