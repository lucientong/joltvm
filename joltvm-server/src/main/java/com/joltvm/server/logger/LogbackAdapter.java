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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Logback adapter using reflection to avoid compile-time dependency.
 *
 * <p>Detects Logback by checking for {@code ch.qos.logback.classic.LoggerContext}.
 */
public class LogbackAdapter implements LoggerAdapter {

    private static final Logger LOG = Logger.getLogger(LogbackAdapter.class.getName());

    @Override
    public String frameworkName() {
        return "Logback";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("ch.qos.logback.classic.LoggerContext");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> listLoggers() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            // ILoggerFactory factory = LoggerFactory.getILoggerFactory()
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getILoggerFactory = loggerFactoryClass.getMethod("getILoggerFactory");
            Object factory = getILoggerFactory.invoke(null);

            // LoggerContext.getLoggerList()
            Class<?> loggerContextClass = Class.forName("ch.qos.logback.classic.LoggerContext");
            Method getLoggerList = loggerContextClass.getMethod("getLoggerList");
            @SuppressWarnings("unchecked")
            List<Object> loggers = (List<Object>) getLoggerList.invoke(factory);

            Class<?> logbackLoggerClass = Class.forName("ch.qos.logback.classic.Logger");
            Method getName = logbackLoggerClass.getMethod("getName");
            Method getLevel = logbackLoggerClass.getMethod("getLevel");
            Method getEffectiveLevel = logbackLoggerClass.getMethod("getEffectiveLevel");

            for (Object logger : loggers) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", getName.invoke(logger));
                Object level = getLevel.invoke(logger);
                info.put("level", level != null ? level.toString() : null);
                Object effectiveLevel = getEffectiveLevel.invoke(logger);
                info.put("effectiveLevel", effectiveLevel != null ? effectiveLevel.toString() : null);
                result.add(info);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to list Logback loggers", e);
        }
        return result;
    }

    @Override
    public Map<String, String> setLevel(String loggerName, String level) {
        try {
            Class<?> loggerFactoryClass = Class.forName("org.slf4j.LoggerFactory");
            Method getLogger = loggerFactoryClass.getMethod("getLogger", String.class);
            Object logger = getLogger.invoke(null, loggerName);

            Class<?> logbackLoggerClass = Class.forName("ch.qos.logback.classic.Logger");
            Class<?> levelClass = Class.forName("ch.qos.logback.classic.Level");

            Method getLevel = logbackLoggerClass.getMethod("getLevel");
            Method getEffectiveLevel = logbackLoggerClass.getMethod("getEffectiveLevel");
            Object previousLevel = getLevel.invoke(logger);
            Object previousEffective = getEffectiveLevel.invoke(logger);
            String previousStr = previousLevel != null ? previousLevel.toString()
                    : (previousEffective != null ? previousEffective.toString() + " (inherited)" : "null");

            // Level.toLevel(String)
            Method toLevel = levelClass.getMethod("toLevel", String.class);
            Object newLevel = toLevel.invoke(null, level.toUpperCase());

            // logger.setLevel(newLevel)
            Method setLevel = logbackLoggerClass.getMethod("setLevel", levelClass);
            setLevel.invoke(logger, newLevel);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("previousLevel", previousStr);
            result.put("newLevel", newLevel.toString());
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to set Logback level: " + e.getMessage(), e);
        }
    }
}
