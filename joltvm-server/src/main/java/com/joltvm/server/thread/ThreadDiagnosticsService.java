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

package com.joltvm.server.thread;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for JVM thread diagnostics.
 *
 * <p>Provides thread listing, CPU top-N analysis, deadlock detection,
 * and standard thread dump generation — all via {@link ThreadMXBean}.
 *
 * <h3>Capabilities:</h3>
 * <ul>
 *   <li>List all threads with state, priority, daemon flag, lock info</li>
 *   <li>Identify top-N CPU-consuming threads via two-sample delta</li>
 *   <li>Detect deadlocks (both object monitor and ownable synchronizer)</li>
 *   <li>Generate jstack-compatible thread dump text</li>
 * </ul>
 *
 * <p>Thread-safe: all methods are safe to call from concurrent HTTP handlers.
 */
public class ThreadDiagnosticsService {

    private static final Logger LOG = Logger.getLogger(ThreadDiagnosticsService.class.getName());

    private final ThreadMXBean threadMXBean;

    /** Cache for CPU top-N results to avoid excessive sampling under concurrent requests. */
    private volatile CpuTopResult cachedTopResult;
    private static final long TOP_CACHE_TTL_MS = 5_000;

    public ThreadDiagnosticsService() {
        this(ManagementFactory.getThreadMXBean());
    }

    // Visible for testing
    public ThreadDiagnosticsService(ThreadMXBean threadMXBean) {
        this.threadMXBean = threadMXBean;
        // Enable CPU time measurement if supported
        if (threadMXBean.isThreadCpuTimeSupported() && !threadMXBean.isThreadCpuTimeEnabled()) {
            try {
                threadMXBean.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException e) {
                LOG.log(Level.WARNING, "Thread CPU time measurement not supported", e);
            }
        }
    }

    /**
     * Returns all live threads with their state, lock info, and basic metrics.
     *
     * @param stateFilter optional thread state filter (null = all threads)
     * @return list of thread summary maps, sorted by thread ID
     */
    public List<Map<String, Object>> getAllThreads(Thread.State stateFilter) {
        ThreadInfo[] infos = threadMXBean.dumpAllThreads(true, true);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ThreadInfo info : infos) {
            if (stateFilter != null && info.getThreadState() != stateFilter) {
                continue;
            }
            result.add(buildThreadSummary(info));
        }

        result.sort(Comparator.comparingLong(m -> (long) m.get("id")));
        return result;
    }

    /**
     * Returns detailed information about a specific thread.
     *
     * @param threadId the thread ID
     * @return thread detail map, or null if thread not found
     */
    public Map<String, Object> getThreadDetail(long threadId) {
        if (threadId <= 0) {
            return null;
        }
        ThreadInfo info;
        try {
            info = threadMXBean.getThreadInfo(threadId, Integer.MAX_VALUE);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (info == null) {
            return null;
        }

        Map<String, Object> detail = buildThreadSummary(info);

        // Full stack trace
        List<Map<String, Object>> stackFrames = new ArrayList<>();
        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();

        for (int i = 0; i < stack.length; i++) {
            Map<String, Object> frame = new LinkedHashMap<>();
            StackTraceElement ste = stack[i];
            frame.put("className", ste.getClassName());
            frame.put("methodName", ste.getMethodName());
            frame.put("fileName", ste.getFileName());
            frame.put("lineNumber", ste.getLineNumber());
            frame.put("nativeMethod", ste.isNativeMethod());

            // Check for monitors locked at this frame
            List<String> lockedMonitors = new ArrayList<>();
            for (MonitorInfo mi : monitors) {
                if (mi.getLockedStackDepth() == i) {
                    lockedMonitors.add(mi.getClassName() + "@"
                            + Integer.toHexString(mi.getIdentityHashCode()));
                }
            }
            if (!lockedMonitors.isEmpty()) {
                frame.put("lockedMonitors", lockedMonitors);
            }

            stackFrames.add(frame);
        }
        detail.put("stackTrace", stackFrames);

        // Locked synchronizers
        LockInfo[] synchronizers = info.getLockedSynchronizers();
        if (synchronizers.length > 0) {
            List<String> syncList = new ArrayList<>();
            for (LockInfo li : synchronizers) {
                syncList.add(li.getClassName() + "@" + Integer.toHexString(li.getIdentityHashCode()));
            }
            detail.put("lockedSynchronizers", syncList);
        }

        return detail;
    }

    /**
     * Returns the top-N threads by CPU consumption.
     *
     * <p>Uses a two-sample approach: measures CPU time at T0, waits for the specified
     * interval, measures again at T1, and computes the delta. Results are cached for
     * {@value #TOP_CACHE_TTL_MS}ms to avoid excessive sampling under concurrent requests.
     *
     * @param n        number of top threads to return
     * @param intervalMs sampling interval in milliseconds (default 1000, min 100, max 5000)
     * @return list of thread maps with cpuDeltaNanos and cpuPercent fields
     */
    public List<Map<String, Object>> getTopThreadsByCpu(int n, long intervalMs) {
        if (!threadMXBean.isThreadCpuTimeSupported()) {
            return List.of(); // Graceful degradation
        }

        int limit = Math.max(1, Math.min(n, 100));
        long interval = Math.max(100, Math.min(intervalMs, 5000));

        // Check cache
        CpuTopResult cached = cachedTopResult;
        if (cached != null && cached.limit == limit
                && System.currentTimeMillis() - cached.timestamp < TOP_CACHE_TTL_MS) {
            return cached.result;
        }

        // Two-sample CPU measurement
        long[] threadIds = threadMXBean.getAllThreadIds();
        Map<Long, Long> cpuT0 = new ConcurrentHashMap<>();
        for (long id : threadIds) {
            long cpuTime = threadMXBean.getThreadCpuTime(id);
            if (cpuTime >= 0) {
                cpuT0.put(id, cpuTime);
            }
        }

        try {
            Thread.sleep(interval);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        ThreadInfo[] allInfos = threadMXBean.getThreadInfo(threadMXBean.getAllThreadIds(), 0);

        for (ThreadInfo info : allInfos) {
            if (info == null) continue;
            long id = info.getThreadId();
            long cpuT1 = threadMXBean.getThreadCpuTime(id);
            if (cpuT1 < 0) continue;

            Long t0 = cpuT0.get(id);
            long delta = (t0 != null) ? cpuT1 - t0 : 0;
            double cpuPercent = (t0 != null && interval > 0)
                    ? (delta / 1_000_000.0) / interval * 100.0 : 0.0;

            Map<String, Object> entry = buildThreadSummary(info);
            entry.put("cpuDeltaNanos", delta);
            entry.put("cpuPercent", Math.round(cpuPercent * 100.0) / 100.0);
            result.add(entry);
        }

        result.sort((a, b) -> Long.compare(
                (long) b.getOrDefault("cpuDeltaNanos", 0L),
                (long) a.getOrDefault("cpuDeltaNanos", 0L)));

        List<Map<String, Object>> topN = result.size() > limit
                ? Collections.unmodifiableList(result.subList(0, limit))
                : Collections.unmodifiableList(result);

        cachedTopResult = new CpuTopResult(topN, limit, System.currentTimeMillis());
        return topN;
    }

    /**
     * Detects deadlocked threads.
     *
     * @return list of deadlock info maps, empty if no deadlocks
     */
    public List<Map<String, Object>> detectDeadlocks() {
        // Check both object monitor deadlocks and ownable synchronizer deadlocks
        long[] deadlockedIds = threadMXBean.findDeadlockedThreads();
        if (deadlockedIds == null) {
            // Fall back to monitor-only detection
            deadlockedIds = threadMXBean.findMonitorDeadlockedThreads();
        }
        if (deadlockedIds == null || deadlockedIds.length == 0) {
            return List.of();
        }

        ThreadInfo[] deadlockedInfos = threadMXBean.getThreadInfo(deadlockedIds, Integer.MAX_VALUE);
        List<Map<String, Object>> result = new ArrayList<>();

        for (ThreadInfo info : deadlockedInfos) {
            if (info == null) continue;
            Map<String, Object> entry = buildThreadSummary(info);

            // Add lock waiting info
            LockInfo lockInfo = info.getLockInfo();
            if (lockInfo != null) {
                entry.put("waitingForLock", lockInfo.getClassName() + "@"
                        + Integer.toHexString(lockInfo.getIdentityHashCode()));
            }
            entry.put("lockOwnerId", info.getLockOwnerId());
            entry.put("lockOwnerName", info.getLockOwnerName());

            // Add abbreviated stack trace (top 10 frames)
            StackTraceElement[] stack = info.getStackTrace();
            List<String> stackLines = new ArrayList<>();
            int maxFrames = Math.min(stack.length, 10);
            for (int i = 0; i < maxFrames; i++) {
                stackLines.add(stack[i].toString());
            }
            if (stack.length > 10) {
                stackLines.add("... " + (stack.length - 10) + " more frames");
            }
            entry.put("stackTrace", stackLines);

            result.add(entry);
        }

        return result;
    }

    /**
     * Generates a standard jstack-compatible thread dump as plain text.
     *
     * @return the thread dump text
     */
    public String generateThreadDump() {
        StringBuilder sb = new StringBuilder();

        // Header
        sb.append("Full thread dump ");
        sb.append(System.getProperty("java.vm.name"));
        sb.append(" (").append(System.getProperty("java.vm.version")).append("):\n\n");

        ThreadInfo[] infos = threadMXBean.dumpAllThreads(true, true);

        for (ThreadInfo info : infos) {
            // Thread header line
            sb.append('"').append(info.getThreadName()).append('"');
            sb.append(" #").append(info.getThreadId());
            if (info.isDaemon()) {
                sb.append(" daemon");
            }
            sb.append(" prio=").append(Thread.NORM_PRIORITY);
            sb.append(" java.lang.Thread.State: ").append(info.getThreadState());

            LockInfo lockInfo = info.getLockInfo();
            if (lockInfo != null) {
                switch (info.getThreadState()) {
                    case BLOCKED ->
                        sb.append(" (on object monitor)");
                    case WAITING, TIMED_WAITING ->
                        sb.append(" (parking)");
                    default -> {}
                }
            }
            sb.append('\n');

            // Stack trace
            StackTraceElement[] stack = info.getStackTrace();
            MonitorInfo[] monitors = info.getLockedMonitors();

            for (int i = 0; i < stack.length; i++) {
                sb.append("\tat ").append(stack[i]).append('\n');

                // Lock info at first frame
                if (i == 0 && lockInfo != null) {
                    switch (info.getThreadState()) {
                        case BLOCKED ->
                            sb.append("\t- waiting to lock <")
                              .append(Integer.toHexString(lockInfo.getIdentityHashCode()))
                              .append("> (a ").append(lockInfo.getClassName()).append(")\n");
                        case WAITING, TIMED_WAITING ->
                            sb.append("\t- waiting on <")
                              .append(Integer.toHexString(lockInfo.getIdentityHashCode()))
                              .append("> (a ").append(lockInfo.getClassName()).append(")\n");
                        default -> {}
                    }
                }

                // Locked monitors at this depth
                for (MonitorInfo mi : monitors) {
                    if (mi.getLockedStackDepth() == i) {
                        sb.append("\t- locked <")
                          .append(Integer.toHexString(mi.getIdentityHashCode()))
                          .append("> (a ").append(mi.getClassName()).append(")\n");
                    }
                }
            }

            // Locked synchronizers
            LockInfo[] synchronizers = info.getLockedSynchronizers();
            if (synchronizers.length > 0) {
                sb.append("\n\tNumber of locked synchronizers = ").append(synchronizers.length).append('\n');
                for (LockInfo li : synchronizers) {
                    sb.append("\t- <").append(Integer.toHexString(li.getIdentityHashCode()))
                      .append("> (a ").append(li.getClassName()).append(")\n");
                }
            }

            sb.append('\n');
        }

        // Deadlock section
        long[] deadlocked = threadMXBean.findDeadlockedThreads();
        if (deadlocked != null && deadlocked.length > 0) {
            sb.append("Found ").append(deadlocked.length)
              .append(" deadlocked thread(s):\n");
            sb.append("=".repeat(40)).append('\n');
            for (long id : deadlocked) {
                ThreadInfo di = threadMXBean.getThreadInfo(id);
                if (di != null) {
                    sb.append('"').append(di.getThreadName()).append('"')
                      .append(" #").append(di.getThreadId()).append('\n');
                }
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Returns thread count summary.
     */
    public Map<String, Object> getThreadCounts() {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("totalStarted", threadMXBean.getTotalStartedThreadCount());
        counts.put("live", threadMXBean.getThreadCount());
        counts.put("daemon", threadMXBean.getDaemonThreadCount());
        counts.put("peak", threadMXBean.getPeakThreadCount());
        return counts;
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private Map<String, Object> buildThreadSummary(ThreadInfo info) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", info.getThreadId());
        map.put("name", info.getThreadName());
        map.put("state", info.getThreadState().name());
        map.put("daemon", info.isDaemon());
        map.put("priority", Thread.NORM_PRIORITY); // ThreadInfo doesn't expose priority directly
        map.put("blockedCount", info.getBlockedCount());
        map.put("blockedTimeMs", info.getBlockedTime());
        map.put("waitedCount", info.getWaitedCount());
        map.put("waitedTimeMs", info.getWaitedTime());

        LockInfo lockInfo = info.getLockInfo();
        if (lockInfo != null) {
            map.put("lockName", lockInfo.toString());
            map.put("lockOwnerId", info.getLockOwnerId());
            map.put("lockOwnerName", info.getLockOwnerName());
        }

        // Include CPU time if available
        if (threadMXBean.isThreadCpuTimeSupported() && threadMXBean.isThreadCpuTimeEnabled()) {
            long cpuTime = threadMXBean.getThreadCpuTime(info.getThreadId());
            long userTime = threadMXBean.getThreadUserTime(info.getThreadId());
            if (cpuTime >= 0) {
                map.put("cpuTimeNanos", cpuTime);
                map.put("userTimeNanos", userTime);
            }
        }

        return map;
    }

    /** Cached CPU top-N result. */
    private record CpuTopResult(List<Map<String, Object>> result, int limit, long timestamp) {
    }
}
