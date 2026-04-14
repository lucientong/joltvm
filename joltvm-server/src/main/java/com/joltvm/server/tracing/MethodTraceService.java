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

import com.joltvm.agent.InstrumentationHolder;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for method-level tracing using Byte Buddy Advice injection.
 *
 * <p>Provides two tracing modes:
 * <ol>
 *   <li><b>Method tracing</b> — Injects Byte Buddy Advice (enter/exit) to capture
 *       method arguments, return values, exceptions, and execution time</li>
 *   <li><b>Stack sampling</b> — Periodically samples thread stacks for flame graph
 *       data collection (configurable interval)</li>
 * </ol>
 *
 * <h3>Usage:</h3>
 * <pre>
 *   MethodTraceService service = new MethodTraceService(collector);
 *   // Trace a specific class+method
 *   service.startTrace("com.example.MyService", "handleRequest", 30);
 *   // Or start stack sampling
 *   service.startSampling(10, 30);
 *   // Get trace records
 *   List&lt;TraceRecord&gt; records = service.getCollector().getRecords();
 * </pre>
 *
 * <p>Thread-safe: all state management uses atomic operations and concurrent collections.
 */
public class MethodTraceService {

    private static final Logger LOG = Logger.getLogger(MethodTraceService.class.getName());

    /** Maximum trace duration in seconds. */
    static final int MAX_TRACE_DURATION_SECONDS = 300;

    /** Default trace duration in seconds. */
    static final int DEFAULT_TRACE_DURATION_SECONDS = 30;

    /** Default sampling interval in milliseconds. */
    static final int DEFAULT_SAMPLING_INTERVAL_MS = 10;

    /** Minimum sampling interval in milliseconds. */
    static final int MIN_SAMPLING_INTERVAL_MS = 1;

    /** Maximum sampling interval in milliseconds. */
    static final int MAX_SAMPLING_INTERVAL_MS = 1000;

    private final FlameGraphCollector collector;
    private final AtomicBoolean tracing = new AtomicBoolean(false);
    private final AtomicBoolean sampling = new AtomicBoolean(false);
    private final Set<String> tracedClasses = ConcurrentHashMap.newKeySet();

    private volatile ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> traceStopFuture;
    private volatile ScheduledFuture<?> samplingFuture;
    private volatile ScheduledFuture<?> samplingStopFuture;
    private volatile String currentTraceTarget;

    /** Shared storage for trace records from the Advice callback. */
    private static volatile FlameGraphCollector activeCollector;

    /**
     * Returns the currently active collector (used by Advice callback).
     *
     * @return the active collector, or null if no trace is active
     */
    public static FlameGraphCollector getActiveCollector() {
        return activeCollector;
    }

    public MethodTraceService() {
        this(new FlameGraphCollector());
    }

    // Visible for testing
    public MethodTraceService(FlameGraphCollector collector) {
        this.collector = collector;
    }

    /**
     * Returns the flame graph data collector.
     *
     * @return the collector instance
     */
    public FlameGraphCollector getCollector() {
        return collector;
    }

    /**
     * Returns whether a method trace is currently active.
     *
     * @return true if tracing is active
     */
    public boolean isTracing() {
        return tracing.get();
    }

    /**
     * Returns whether stack sampling is currently active.
     *
     * @return true if sampling is active
     */
    public boolean isSampling() {
        return sampling.get();
    }

    /**
     * Returns the current trace target descriptor (e.g., "com.example.MyService#handleRequest").
     *
     * @return the trace target, or null if not tracing
     */
    public String getCurrentTraceTarget() {
        return currentTraceTarget;
    }

    /**
     * Returns the set of class names currently being traced.
     *
     * @return unmodifiable set of traced class names
     */
    public Set<String> getTracedClasses() {
        return Collections.unmodifiableSet(tracedClasses);
    }

    /**
     * Starts method tracing on the specified class and method.
     *
     * <p>Uses Byte Buddy Advice to inject enter/exit callbacks that capture:
     * <ul>
     *   <li>Method arguments (toString, truncated to 200 chars)</li>
     *   <li>Return value (toString, truncated to 200 chars)</li>
     *   <li>Execution time in nanoseconds</li>
     *   <li>Exception type and message (if thrown)</li>
     *   <li>Thread name and ID</li>
     * </ul>
     *
     * @param className       the fully qualified class name to trace
     * @param methodName      the method name to trace (null or "*" for all methods)
     * @param durationSeconds how long to trace (auto-stops after this duration)
     * @throws TracingException if tracing cannot be started
     */
    public void startTrace(String className, String methodName, int durationSeconds) {
        if (className == null || className.isBlank()) {
            throw new TracingException("Class name is required for tracing");
        }

        if (!InstrumentationHolder.isAvailable()) {
            throw new TracingException("Instrumentation not available. Agent may not be loaded.");
        }

        if (tracing.get()) {
            throw new TracingException("A trace is already active on: " + currentTraceTarget
                    + ". Stop the current trace first.");
        }

        int duration = normalizeDuration(durationSeconds);
        String effectiveMethod = (methodName == null || methodName.isBlank() || "*".equals(methodName))
                ? "*" : methodName;
        currentTraceTarget = className + "#" + effectiveMethod;

        LOG.info("Starting method trace: " + currentTraceTarget + " (duration=" + duration + "s)");

        try {
            // Set up the active collector for the Advice callback
            activeCollector = collector;
            tracing.set(true);
            tracedClasses.add(className);

            // Install Byte Buddy Advice
            Instrumentation inst = InstrumentationHolder.get();

            ElementMatcher<? super TypeDescription> typeMatcher =
                    ElementMatchers.named(className);

            ElementMatcher<? super MethodDescription> methodMatcher;
            if ("*".equals(effectiveMethod)) {
                methodMatcher = ElementMatchers.isMethod()
                        .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                        .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()));
            } else {
                methodMatcher = ElementMatchers.named(effectiveMethod);
            }

            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(ElementMatchers.nameStartsWith("com.joltvm."))
                    .type(typeMatcher)
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(MethodTraceAdvice.class).on(methodMatcher)))
                    .installOn(inst);

            LOG.info("Byte Buddy Advice installed for: " + currentTraceTarget);

            // Schedule auto-stop
            ensureScheduler();
            traceStopFuture = scheduler.schedule(() -> {
                LOG.info("Auto-stopping trace after " + duration + "s");
                stopTrace();
            }, duration, TimeUnit.SECONDS);

        } catch (TracingException e) {
            tracing.set(false);
            activeCollector = null;
            currentTraceTarget = null;
            throw e;
        } catch (Exception e) {
            tracing.set(false);
            activeCollector = null;
            currentTraceTarget = null;
            throw new TracingException("Failed to start tracing: " + e.getMessage(), e);
        }
    }

    /**
     * Stops the currently active method trace.
     *
     * <p>Note: The Byte Buddy transformation remains in the JVM until the class
     * is retransformed. The trace is logically stopped by setting the active collector
     * to null, which causes the Advice callback to skip recording.
     */
    public void stopTrace() {
        if (!tracing.compareAndSet(true, false)) {
            return;
        }

        LOG.info("Stopping method trace: " + currentTraceTarget);
        activeCollector = null;

        if (traceStopFuture != null) {
            traceStopFuture.cancel(false);
            traceStopFuture = null;
        }

        // Retransform traced classes to remove Advice (best effort)
        if (InstrumentationHolder.isAvailable()) {
            Instrumentation inst = InstrumentationHolder.get();
            for (String className : tracedClasses) {
                try {
                    for (Class<?> clazz : inst.getAllLoadedClasses()) {
                        if (clazz.getName().equals(className) && inst.isModifiableClass(clazz)) {
                            inst.retransformClasses(clazz);
                            LOG.fine("Retransformed class: " + className);
                            break;
                        }
                    }
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to retransform class: " + className, e);
                }
            }
        }

        tracedClasses.clear();
        currentTraceTarget = null;
    }

    /**
     * Starts periodic stack sampling for flame graph data collection.
     *
     * <p>Samples all non-daemon threads at the specified interval. Each sample
     * captures the stack trace and feeds it to the flame graph collector.
     *
     * @param intervalMs      sampling interval in milliseconds
     * @param durationSeconds how long to sample (auto-stops after this duration)
     * @throws TracingException if sampling cannot be started
     */
    public void startSampling(int intervalMs, int durationSeconds) {
        if (sampling.get()) {
            throw new TracingException("Stack sampling is already active. Stop it first.");
        }

        int interval = Math.max(MIN_SAMPLING_INTERVAL_MS,
                Math.min(MAX_SAMPLING_INTERVAL_MS, intervalMs));
        int duration = normalizeDuration(durationSeconds);

        LOG.info("Starting stack sampling (interval=" + interval + "ms, duration=" + duration + "s)");
        sampling.set(true);

        ensureScheduler();

        // Schedule periodic sampling
        samplingFuture = scheduler.scheduleAtFixedRate(() -> {
            try {
                sampleAllThreads();
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error during stack sampling", e);
            }
        }, 0, interval, TimeUnit.MILLISECONDS);

        // Schedule auto-stop
        samplingStopFuture = scheduler.schedule(() -> {
            LOG.info("Auto-stopping sampling after " + duration + "s");
            stopSampling();
        }, duration, TimeUnit.SECONDS);
    }

    /**
     * Stops the currently active stack sampling.
     */
    public void stopSampling() {
        if (!sampling.compareAndSet(true, false)) {
            return;
        }

        LOG.info("Stopping stack sampling");

        if (samplingFuture != null) {
            samplingFuture.cancel(false);
            samplingFuture = null;
        }
        if (samplingStopFuture != null) {
            samplingStopFuture.cancel(false);
            samplingStopFuture = null;
        }
    }

    /**
     * Stops all active tracing and sampling, and clears collected data.
     */
    public void reset() {
        stopTrace();
        stopSampling();
        collector.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /**
     * Returns a summary of the current tracing/sampling state.
     *
     * @return a map with state information
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("tracing", tracing.get());
        status.put("sampling", sampling.get());
        status.put("traceTarget", currentTraceTarget);
        status.put("tracedClasses", new ArrayList<>(tracedClasses));
        status.put("recordCount", collector.getRecordCount());
        status.put("sampleCount", collector.getSampleCount());
        return status;
    }

    // ========================================================================
    // Internal helpers
    // ========================================================================

    private void sampleAllThreads() {
        Map<Thread, StackTraceElement[]> stacks = Thread.getAllStackTraces();
        for (Map.Entry<Thread, StackTraceElement[]> entry : stacks.entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();

            // Skip daemon threads, JoltVM threads, and empty stacks
            if (thread.isDaemon() || stack.length == 0) {
                continue;
            }
            if (thread.getName().startsWith("joltvm-")) {
                continue;
            }

            collector.addStackSample(stack);
        }
    }

    private int normalizeDuration(int durationSeconds) {
        if (durationSeconds <= 0) {
            return DEFAULT_TRACE_DURATION_SECONDS;
        }
        return Math.min(durationSeconds, MAX_TRACE_DURATION_SECONDS);
    }

    private void ensureScheduler() {
        if (scheduler == null || scheduler.isShutdown()) {
            scheduler = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "joltvm-trace-scheduler");
                t.setDaemon(true);
                return t;
            });
        }
    }

    // ========================================================================
    // Byte Buddy Advice for method tracing
    // ========================================================================

    /**
     * Byte Buddy Advice class injected into traced methods.
     *
     * <p>The {@code @Advice.OnMethodEnter} captures the start time.
     * The {@code @Advice.OnMethodExit} captures the return value, exception, and duration,
     * then records a {@link TraceRecord} in the active {@link FlameGraphCollector}.
     */
    public static class MethodTraceAdvice {

        /**
         * Called before the traced method body executes.
         *
         * @return the start time in nanoseconds
         */
        @Advice.OnMethodEnter
        public static long onEnter() {
            return System.nanoTime();
        }

        /**
         * Called after the traced method body executes (on both normal return and exception).
         *
         * @param startTime      the start time from onEnter
         * @param returnValue    the return value (null if void or exception)
         * @param thrown         the exception thrown (null if no exception)
         * @param methodName     the traced method name
         * @param declaringType  the declaring class name
         * @param parameterTypes the parameter type names
         * @param arguments      the method arguments
         */
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void onExit(
                @Advice.Enter long startTime,
                @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
                @Advice.Thrown Throwable thrown,
                @Advice.Origin("#m") String methodName,
                @Advice.Origin("#t") String declaringType,
                @Advice.Origin("#s") String signature,
                @Advice.AllArguments Object[] arguments) {

            FlameGraphCollector collector = MethodTraceService.getActiveCollector();
            if (collector == null) {
                return; // Tracing stopped
            }

            long durationNanos = System.nanoTime() - startTime;
            Thread currentThread = Thread.currentThread();

            // Extract parameter types from signature
            List<String> paramTypes = extractParamTypes(signature);

            // Convert arguments to strings (truncated)
            List<String> argStrings = new ArrayList<>();
            if (arguments != null) {
                for (Object arg : arguments) {
                    argStrings.add(truncate(safeToString(arg), 200));
                }
            }

            String returnStr = (thrown == null && returnValue != null)
                    ? truncate(safeToString(returnValue), 200) : null;
            String exType = thrown != null ? thrown.getClass().getName() : null;
            String exMsg = thrown != null ? truncate(safeToString(thrown.getMessage()), 200) : null;

            TraceRecord record = new TraceRecord(
                    UUID.randomUUID().toString().substring(0, 8),
                    declaringType,
                    methodName,
                    paramTypes,
                    argStrings,
                    returnStr,
                    exType,
                    exMsg,
                    durationNanos,
                    currentThread.getName(),
                    currentThread.getId(),
                    Instant.now(),
                    0 // depth is 0 for direct tracing; call chain depth requires more complex tracking
            );

            collector.addRecord(record);
        }

        private static String safeToString(Object obj) {
            if (obj == null) {
                return "null";
            }
            try {
                return obj.toString();
            } catch (Exception e) {
                return obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
            }
        }

        private static String truncate(String s, int maxLength) {
            if (s == null) {
                return null;
            }
            return s.length() > maxLength ? s.substring(0, maxLength) + "..." : s;
        }

        private static List<String> extractParamTypes(String signature) {
            // signature format: "methodName(Type1, Type2)"
            int openParen = signature.indexOf('(');
            int closeParen = signature.lastIndexOf(')');
            if (openParen < 0 || closeParen < 0 || closeParen <= openParen + 1) {
                return List.of();
            }
            String params = signature.substring(openParen + 1, closeParen);
            if (params.isBlank()) {
                return List.of();
            }
            return Arrays.stream(params.split(","))
                    .map(String::trim)
                    .toList();
        }
    }
}
