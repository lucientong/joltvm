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

package com.joltvm.server.jvm;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Service for JVM runtime information: GC statistics, system properties,
 * environment variables, and classpath entries.
 *
 * <p>Sensitive keys (passwords, secrets, tokens) are automatically redacted
 * in system properties and environment variable outputs.
 */
public class JvmInfoService {

    /** Pattern matching keys that likely contain secrets. Case-insensitive. */
    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
            "(?i).*(password|passwd|secret|token|credential|api[_-]?key|private[_-]?key|auth).*");

    private static final String REDACTED = "******";

    /**
     * Returns GC statistics for all garbage collectors.
     *
     * @return map with collectors list and overall overhead percentage
     */
    public Map<String, Object> getGcStats() {
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

        List<Map<String, Object>> collectors = new ArrayList<>();
        long totalGcTimeMs = 0;

        for (GarbageCollectorMXBean gc : gcBeans) {
            Map<String, Object> collector = new LinkedHashMap<>();
            collector.put("name", gc.getName());
            collector.put("collectionCount", gc.getCollectionCount());
            collector.put("collectionTimeMs", gc.getCollectionTime());
            String[] pools = gc.getMemoryPoolNames();
            collector.put("memoryPools", pools != null ? List.of(pools) : List.of());
            collectors.add(collector);

            if (gc.getCollectionTime() > 0) {
                totalGcTimeMs += gc.getCollectionTime();
            }
        }

        long uptimeMs = runtime.getUptime();
        double overheadPercent = uptimeMs > 0
                ? Math.round(totalGcTimeMs * 10000.0 / uptimeMs) / 100.0
                : 0.0;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("collectors", collectors);
        result.put("totalGcTimeMs", totalGcTimeMs);
        result.put("uptimeMs", uptimeMs);
        result.put("overheadPercent", overheadPercent);
        return result;
    }

    /**
     * Returns system properties with sensitive values redacted.
     *
     * @return map with sorted properties list
     */
    public Map<String, Object> getSystemProperties() {
        Map<String, String> sorted = new TreeMap<>();
        try {
            for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
                String key = String.valueOf(entry.getKey());
                String value = String.valueOf(entry.getValue());
                sorted.put(key, isSensitiveKey(key) ? REDACTED : value);
            }
        } catch (SecurityException e) {
            // SecurityManager may block access
            sorted.put("_error", "Cannot read system properties: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> properties = new ArrayList<>();
        sorted.forEach((k, v) -> properties.add(Map.of("key", k, "value", v)));
        result.put("properties", properties);
        result.put("count", properties.size());
        return result;
    }

    /**
     * Returns environment variables with sensitive values redacted.
     *
     * @return map with sorted variables list
     */
    public Map<String, Object> getEnvironmentVariables() {
        Map<String, String> sorted = new TreeMap<>();
        try {
            for (Map.Entry<String, String> entry : System.getenv().entrySet()) {
                sorted.put(entry.getKey(),
                        isSensitiveKey(entry.getKey()) ? REDACTED : entry.getValue());
            }
        } catch (SecurityException e) {
            sorted.put("_error", "Cannot read environment variables: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> variables = new ArrayList<>();
        sorted.forEach((k, v) -> variables.add(Map.of("key", k, "value", v)));
        result.put("variables", variables);
        result.put("count", variables.size());
        return result;
    }

    /**
     * Returns the runtime classpath entries.
     *
     * @return map with classpath entries and module path if available
     */
    public Map<String, Object> getClasspath() {
        String classPath = System.getProperty("java.class.path", "");
        String modulePath = System.getProperty("jdk.module.path", "");

        List<Map<String, Object>> entries = new ArrayList<>();
        if (!classPath.isEmpty()) {
            for (String path : classPath.split(File.pathSeparator)) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("path", path);
                File f = new File(path);
                entry.put("exists", f.exists());
                if (f.isFile()) {
                    entry.put("type", "file");
                    entry.put("sizeBytes", f.length());
                } else if (f.isDirectory()) {
                    entry.put("type", "directory");
                } else {
                    entry.put("type", "unknown");
                }
                entries.add(entry);
            }
        }

        List<String> moduleEntries = new ArrayList<>();
        if (!modulePath.isEmpty()) {
            for (String path : modulePath.split(File.pathSeparator)) {
                moduleEntries.add(path);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("entries", entries);
        result.put("count", entries.size());
        if (!moduleEntries.isEmpty()) {
            result.put("modulePath", moduleEntries);
        }
        return result;
    }

    /**
     * Checks if a key likely contains sensitive information.
     */
    static boolean isSensitiveKey(String key) {
        return SENSITIVE_KEY_PATTERN.matcher(key).matches();
    }
}
