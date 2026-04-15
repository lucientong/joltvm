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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * Java Util Logging (JUL) adapter — always available as the JDK built-in fallback.
 */
public class JulAdapter implements LoggerAdapter {

    @Override
    public String frameworkName() {
        return "JUL";
    }

    @Override
    public boolean isAvailable() {
        return true; // Always available
    }

    @Override
    public List<Map<String, Object>> listLoggers() {
        List<Map<String, Object>> result = new ArrayList<>();
        LogManager logManager = LogManager.getLogManager();
        Enumeration<String> names = logManager.getLoggerNames();
        List<String> sortedNames = new ArrayList<>();
        while (names.hasMoreElements()) {
            sortedNames.add(names.nextElement());
        }
        Collections.sort(sortedNames);

        for (String name : sortedNames) {
            Logger logger = logManager.getLogger(name);
            if (logger != null) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", name.isEmpty() ? "ROOT" : name);
                Level level = logger.getLevel();
                info.put("level", level != null ? level.getName() : null);
                Level effective = getEffectiveLevel(logger);
                info.put("effectiveLevel", effective != null ? effective.getName() : null);
                result.add(info);
            }
        }
        return result;
    }

    @Override
    public Map<String, String> setLevel(String loggerName, String level) {
        // Handle "ROOT" as the root logger
        String actualName = "ROOT".equals(loggerName) ? "" : loggerName;

        Logger logger = LogManager.getLogManager().getLogger(actualName);
        if (logger == null) {
            // Create the logger if it doesn't exist
            logger = Logger.getLogger(actualName);
        }

        Level previousLevel = logger.getLevel();
        Level effectiveLevel = getEffectiveLevel(logger);
        String previousStr = previousLevel != null ? previousLevel.getName()
                : (effectiveLevel != null ? effectiveLevel.getName() + " (inherited)" : "null");

        Level newLevel = mapToJulLevel(level.toUpperCase());
        logger.setLevel(newLevel);

        Map<String, String> result = new LinkedHashMap<>();
        result.put("previousLevel", previousStr);
        result.put("newLevel", newLevel.getName());
        return result;
    }

    private Level getEffectiveLevel(Logger logger) {
        Logger current = logger;
        while (current != null) {
            Level level = current.getLevel();
            if (level != null) return level;
            current = current.getParent();
        }
        return Level.INFO; // JVM default
    }

    /**
     * Maps standard logging level names to JUL levels.
     */
    static Level mapToJulLevel(String level) {
        return switch (level) {
            case "TRACE" -> Level.FINEST;
            case "DEBUG" -> Level.FINE;
            case "INFO" -> Level.INFO;
            case "WARN", "WARNING" -> Level.WARNING;
            case "ERROR" -> Level.SEVERE;
            case "OFF" -> Level.OFF;
            case "ALL" -> Level.ALL;
            default -> {
                try {
                    yield Level.parse(level);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid log level: " + level + ". Valid values: TRACE, DEBUG, INFO, WARN, ERROR, OFF, ALL");
                }
            }
        };
    }
}
