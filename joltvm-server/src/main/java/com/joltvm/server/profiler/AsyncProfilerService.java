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

package com.joltvm.server.profiler;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for async-profiler integration via reflection (no compile-time dependency).
 *
 * <p>Detects async-profiler availability by searching:
 * <ol>
 *   <li>System property {@code asyncprofiler.path}</li>
 *   <li>Environment variable {@code ASYNC_PROFILER_PATH}</li>
 *   <li>Common installation paths: {@code /usr/local/lib/libasyncProfiler.*},
 *       {@code /opt/async-profiler/lib/libasyncProfiler.*}</li>
 *   <li>Java API: {@code one.profiler.AsyncProfiler} class</li>
 * </ol>
 *
 * <p>Supports profiling modes: CPU, Alloc, Lock, Wall-clock.
 * Auto-fallback from {@code cpu} to {@code itimer} if {@code perf_event_paranoid} blocks.
 *
 * <p>Output is collapsed stack format, converted to d3-flamegraph-compatible JSON.
 */
public class AsyncProfilerService {

    private static final Logger LOG = Logger.getLogger(AsyncProfilerService.class.getName());

    /** Supported profiling events. */
    public enum Event {
        CPU("cpu"), ALLOC("alloc"), LOCK("lock"), WALL("wall"), ITIMER("itimer");

        final String value;
        Event(String v) { this.value = v; }
        public String getValue() { return value; }
    }

    private final AtomicBoolean profiling = new AtomicBoolean(false);
    private final ConcurrentHashMap<String, ProfileSession> sessions = new ConcurrentHashMap<>();

    private volatile Object asyncProfilerInstance; // one.profiler.AsyncProfiler
    private volatile Method executeMethod;
    private volatile String nativeLibPath;
    private volatile boolean available;
    private volatile String unavailableReason;

    public AsyncProfilerService() {
        detectAsyncProfiler();
    }

    /**
     * Returns the profiler status: availability, platform, active session.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("available", available);
        result.put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        result.put("nativeLibPath", nativeLibPath);
        if (!available) {
            result.put("unavailableReason", unavailableReason);
        }
        result.put("profiling", profiling.get());
        result.put("sessionCount", sessions.size());
        result.put("supportedEvents", List.of("cpu", "alloc", "lock", "wall", "itimer"));
        return result;
    }

    /**
     * Starts profiling.
     *
     * @param event    the event type (cpu, alloc, lock, wall)
     * @param duration duration in seconds
     * @param interval sampling interval (ns for cpu, bytes for alloc)
     * @return session info map
     */
    public Map<String, Object> start(String event, int duration, long interval) {
        if (!available) {
            throw new IllegalStateException("async-profiler not available: " + unavailableReason);
        }
        if (!profiling.compareAndSet(false, true)) {
            throw new IllegalStateException("A profiling session is already active");
        }

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        Event eventType = parseEvent(event);

        try {
            // Build command: start,event=cpu,interval=10000000
            StringBuilder cmd = new StringBuilder("start,event=").append(eventType.value);
            if (interval > 0) {
                cmd.append(",interval=").append(interval);
            }
            String startResult = execute(cmd.toString());

            ProfileSession session = new ProfileSession(sessionId, eventType, duration, startResult);
            sessions.put(sessionId, session);

            // Schedule auto-stop
            int effectiveDuration = Math.min(Math.max(duration, 1), 300);
            Thread stopThread = new Thread(() -> {
                try {
                    Thread.sleep(effectiveDuration * 1000L);
                    stopInternal(sessionId);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }, "joltvm-profiler-stop-" + sessionId);
            stopThread.setDaemon(true);
            stopThread.start();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", sessionId);
            result.put("event", eventType.value);
            result.put("duration", effectiveDuration);
            result.put("interval", interval);
            result.put("status", "started");
            result.put("message", startResult);
            return result;

        } catch (Exception e) {
            profiling.set(false);
            // Auto-fallback: cpu -> itimer
            if (eventType == Event.CPU && e.getMessage() != null
                    && e.getMessage().contains("perf_event")) {
                LOG.info("cpu event failed, falling back to itimer");
                return start("itimer", duration, interval);
            }
            throw new IllegalStateException("Failed to start profiling: " + e.getMessage(), e);
        }
    }

    /**
     * Stops the active profiling session.
     *
     * @return session result map with collapsed stacks
     */
    public Map<String, Object> stop() {
        if (!profiling.get()) {
            throw new IllegalStateException("No profiling session is active");
        }

        // Find active session
        String activeId = null;
        for (Map.Entry<String, ProfileSession> entry : sessions.entrySet()) {
            if (entry.getValue().isActive()) {
                activeId = entry.getKey();
                break;
            }
        }

        if (activeId == null) {
            profiling.set(false);
            throw new IllegalStateException("No active session found");
        }

        return stopInternal(activeId);
    }

    private synchronized Map<String, Object> stopInternal(String sessionId) {
        ProfileSession session = sessions.get(sessionId);
        if (session == null || !session.isActive()) {
            return Map.of("sessionId", sessionId, "status", "already_stopped");
        }

        try {
            // Get collapsed stack output
            String collapsed = execute("stop,flat");
            session.complete(collapsed);
            profiling.set(false);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sessionId", sessionId);
            result.put("event", session.getEvent().value);
            result.put("status", "stopped");
            result.put("sampleCount", countSamples(collapsed));
            return result;

        } catch (Exception e) {
            profiling.set(false);
            throw new IllegalStateException("Failed to stop profiling: " + e.getMessage(), e);
        }
    }

    /**
     * Returns the flame graph data for a session in d3-flamegraph JSON format.
     *
     * @param sessionId the session ID
     * @return d3-compatible flame graph JSON structure
     */
    public Map<String, Object> getFlameGraph(String sessionId) {
        ProfileSession session = sessions.get(sessionId);
        if (session == null) {
            return null;
        }

        String collapsed = session.getCollapsedStacks();
        if (collapsed == null || collapsed.isBlank()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("sessionId", sessionId);
            empty.put("status", session.isActive() ? "profiling" : "no_data");
            empty.put("data", Map.of("name", "root", "value", 0, "children", List.of()));
            return empty;
        }

        // Parse collapsed stacks to d3-flamegraph tree
        Map<String, Object> root = parseCollapsedToTree(collapsed);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("event", session.getEvent().value);
        result.put("status", "complete");
        result.put("data", root);
        return result;
    }

    // ---- Detection ----

    private void detectAsyncProfiler() {
        // Try Java API first
        try {
            Class<?> apClass = Class.forName("one.profiler.AsyncProfiler");
            Method getInstance = apClass.getMethod("getInstance");
            asyncProfilerInstance = getInstance.invoke(null);
            executeMethod = apClass.getMethod("execute", String.class);
            available = true;
            nativeLibPath = "Java API (one.profiler.AsyncProfiler)";
            LOG.info("async-profiler detected via Java API");
            return;
        } catch (Exception ignored) {}

        // Try loading native lib from known paths
        String libPath = findNativeLib();
        if (libPath != null) {
            try {
                Class<?> apClass = Class.forName("one.profiler.AsyncProfiler");
                Method getInstance = apClass.getMethod("getInstance", String.class);
                asyncProfilerInstance = getInstance.invoke(null, libPath);
                executeMethod = apClass.getMethod("execute", String.class);
                available = true;
                nativeLibPath = libPath;
                LOG.info("async-profiler detected at: " + libPath);
                return;
            } catch (ClassNotFoundException e) {
                // API class not on classpath — try command-line mode
                nativeLibPath = libPath;
            } catch (Exception e) {
                LOG.log(Level.FINE, "Failed to load async-profiler from " + libPath, e);
            }
        }

        available = false;
        unavailableReason = "async-profiler not found. Install it and set ASYNC_PROFILER_PATH or asyncprofiler.path system property.";
        LOG.info("async-profiler not available: " + unavailableReason);
    }

    private String findNativeLib() {
        // System property
        String path = System.getProperty("asyncprofiler.path");
        if (path != null && new File(path).exists()) return path;

        // Environment variable
        path = System.getenv("ASYNC_PROFILER_PATH");
        if (path != null && new File(path).exists()) return path;

        // Common paths
        String os = System.getProperty("os.name", "").toLowerCase();
        String ext = os.contains("mac") ? ".dylib" : ".so";

        String[] searchPaths = {
                "/usr/local/lib/libasyncProfiler" + ext,
                "/opt/async-profiler/lib/libasyncProfiler" + ext,
                "/usr/lib/libasyncProfiler" + ext,
                System.getProperty("user.home") + "/async-profiler/lib/libasyncProfiler" + ext,
        };

        for (String p : searchPaths) {
            if (new File(p).exists()) return p;
        }

        return null;
    }

    private String execute(String command) {
        if (asyncProfilerInstance != null && executeMethod != null) {
            try {
                return (String) executeMethod.invoke(asyncProfilerInstance, command);
            } catch (Exception e) {
                throw new RuntimeException("async-profiler execute failed: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("async-profiler instance not initialized");
    }

    private Event parseEvent(String event) {
        if (event == null || event.isBlank()) return Event.CPU;
        return switch (event.toLowerCase()) {
            case "cpu" -> Event.CPU;
            case "alloc" -> Event.ALLOC;
            case "lock" -> Event.LOCK;
            case "wall" -> Event.WALL;
            case "itimer" -> Event.ITIMER;
            default -> throw new IllegalArgumentException("Unknown event type: " + event
                    + ". Supported: cpu, alloc, lock, wall, itimer");
        };
    }

    private int countSamples(String collapsed) {
        if (collapsed == null) return 0;
        int count = 0;
        for (String line : collapsed.split("\n")) {
            if (!line.isBlank()) count++;
        }
        return count;
    }

    /**
     * Parses collapsed stack format into d3-flamegraph tree structure.
     * Format: "frame1;frame2;frame3 count\n"
     */
    static Map<String, Object> parseCollapsedToTree(String collapsed) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "root");
        root.put("value", 0);
        root.put("children", new ArrayList<Map<String, Object>>());

        if (collapsed == null || collapsed.isBlank()) return root;

        for (String line : collapsed.split("\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;

            int lastSpace = line.lastIndexOf(' ');
            if (lastSpace < 0) continue;

            String stack = line.substring(0, lastSpace);
            long count;
            try {
                count = Long.parseLong(line.substring(lastSpace + 1));
            } catch (NumberFormatException e) {
                continue;
            }

            String[] frames = stack.split(";");
            addToTree(root, frames, 0, count);
        }

        return root;
    }

    @SuppressWarnings("unchecked")
    private static void addToTree(Map<String, Object> node, String[] frames, int depth, long count) {
        long currentValue = ((Number) node.get("value")).longValue();
        node.put("value", currentValue + count);

        if (depth >= frames.length) return;

        List<Map<String, Object>> children = (List<Map<String, Object>>) node.get("children");
        String frameName = frames[depth];

        Map<String, Object> child = null;
        for (Map<String, Object> c : children) {
            if (frameName.equals(c.get("name"))) {
                child = c;
                break;
            }
        }

        if (child == null) {
            child = new LinkedHashMap<>();
            child.put("name", frameName);
            child.put("value", 0L);
            child.put("children", new ArrayList<Map<String, Object>>());
            children.add(child);
        }

        addToTree(child, frames, depth + 1, count);
    }

    /** Visible for testing. */
    boolean isAvailable() { return available; }

    /** Visible for testing. */
    void setAvailableForTesting(boolean available, String reason) {
        this.available = available;
        this.unavailableReason = reason;
    }

    // ---- Profile Session ----

    static class ProfileSession {
        private final String id;
        private final Event event;
        private final int duration;
        private final String startMessage;
        private volatile String collapsedStacks;
        private volatile boolean active = true;

        ProfileSession(String id, Event event, int duration, String startMessage) {
            this.id = id;
            this.event = event;
            this.duration = duration;
            this.startMessage = startMessage;
        }

        void complete(String collapsed) {
            this.collapsedStacks = collapsed;
            this.active = false;
        }

        boolean isActive() { return active; }
        Event getEvent() { return event; }
        String getCollapsedStacks() { return collapsedStacks; }
    }
}
