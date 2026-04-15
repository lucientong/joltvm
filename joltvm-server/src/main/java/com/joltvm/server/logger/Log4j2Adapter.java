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
 * Log4j2 adapter using reflection to avoid compile-time dependency.
 *
 * <p>Detects Log4j2 by checking for {@code org.apache.logging.log4j.core.LoggerContext}.
 */
public class Log4j2Adapter implements LoggerAdapter {

    private static final Logger LOG = Logger.getLogger(Log4j2Adapter.class.getName());

    @Override
    public String frameworkName() {
        return "Log4j2";
    }

    @Override
    public boolean isAvailable() {
        try {
            Class.forName("org.apache.logging.log4j.core.LoggerContext");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Override
    public List<Map<String, Object>> listLoggers() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            // LoggerContext ctx = (LoggerContext) LogManager.getContext(false)
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Method getContext = logManagerClass.getMethod("getContext", boolean.class);
            Object ctx = getContext.invoke(null, false);

            // ctx.getLoggers()
            Class<?> loggerContextClass = Class.forName("org.apache.logging.log4j.core.LoggerContext");
            Method getLoggers = loggerContextClass.getMethod("getLoggers");
            @SuppressWarnings("unchecked")
            java.util.Collection<Object> loggers = (java.util.Collection<Object>) getLoggers.invoke(ctx);

            Class<?> loggerClass = Class.forName("org.apache.logging.log4j.core.Logger");
            Method getName = loggerClass.getMethod("getName");
            Method getLevel = loggerClass.getMethod("getLevel");

            for (Object logger : loggers) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", getName.invoke(logger));
                Object level = getLevel.invoke(logger);
                info.put("level", level != null ? level.toString() : null);
                info.put("effectiveLevel", level != null ? level.toString() : null);
                result.add(info);
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to list Log4j2 loggers", e);
        }
        return result;
    }

    @Override
    public Map<String, String> setLevel(String loggerName, String level) {
        try {
            Class<?> logManagerClass = Class.forName("org.apache.logging.log4j.LogManager");
            Method getContext = logManagerClass.getMethod("getContext", boolean.class);
            Object ctx = getContext.invoke(null, false);

            // Configuration config = ctx.getConfiguration()
            Class<?> loggerContextClass = Class.forName("org.apache.logging.log4j.core.LoggerContext");
            Method getConfiguration = loggerContextClass.getMethod("getConfiguration");
            Object config = getConfiguration.invoke(ctx);

            // LoggerConfig loggerConfig = config.getLoggerConfig(loggerName)
            Class<?> configClass = Class.forName("org.apache.logging.log4j.core.config.Configuration");
            Method getLoggerConfig = configClass.getMethod("getLoggerConfig", String.class);
            Object loggerConfig = getLoggerConfig.invoke(config, loggerName);

            // Get previous level
            Class<?> loggerConfigClass = Class.forName("org.apache.logging.log4j.core.config.LoggerConfig");
            Method getLevel = loggerConfigClass.getMethod("getLevel");
            Object previousLevel = getLevel.invoke(loggerConfig);
            String previousStr = previousLevel != null ? previousLevel.toString() : "null";

            // Level newLevel = Level.toLevel(level)
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Method toLevel = levelClass.getMethod("toLevel", String.class);
            Object newLevel = toLevel.invoke(null, level.toUpperCase());

            // loggerConfig.setLevel(newLevel)
            Method setLevel = loggerConfigClass.getMethod("setLevel", levelClass);
            setLevel.invoke(loggerConfig, newLevel);

            // ctx.updateLoggers()
            Method updateLoggers = loggerContextClass.getMethod("updateLoggers");
            updateLoggers.invoke(ctx);

            Map<String, String> result = new LinkedHashMap<>();
            result.put("previousLevel", previousStr);
            result.put("newLevel", newLevel.toString());
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to set Log4j2 level: " + e.getMessage(), e);
        }
    }
}
