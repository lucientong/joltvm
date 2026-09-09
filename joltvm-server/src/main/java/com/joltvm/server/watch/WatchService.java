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

package com.joltvm.server.watch;

import com.joltvm.agent.InstrumentationHolder;
import com.joltvm.server.ognl.OgnlService;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import java.lang.instrument.Instrumentation;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service managing multiple concurrent method watch sessions.
 *
 * <p>Each watch session installs its own Byte Buddy Advice transformer,
 * captures invocations matching the class/method pattern, and stores
 * records in a bounded per-session buffer.
 *
 * <p>Optional OGNL {@code conditionExpr} is validated and precompiled at
 * {@link #startWatch}; evaluation runs here (not in {@link WatchAdvice})
 * with context variables {@code #args}, {@code #returnObj}, {@code #throwExp},
 * {@code #cost}, {@code #target}, {@code #clazz}.
 *
 * <p>Hard limit: {@value MAX_CONCURRENT_WATCHES} concurrent watches.
 * Sessions auto-expire after their configured duration.
 */
public class WatchService {

    private static final Logger LOG = Logger.getLogger(WatchService.class.getName());

    /** Maximum concurrent watch sessions. */
    static final int MAX_CONCURRENT_WATCHES = 10;

    /** Default watch duration in milliseconds (60 seconds). */
    static final long DEFAULT_DURATION_MS = 60_000;

    /** Maximum watch duration in milliseconds (5 minutes). */
    static final long MAX_DURATION_MS = 300_000;

    /** Max length for stringified args / return / exception message in records. */
    private static final int TRUNCATE_LEN = 200;

    /** Static bridge for Advice callbacks (Advice must not touch OGNL). */
    private static volatile WatchService bridge;

    /** Active watch sessions (id -> session). */
    private final ConcurrentHashMap<String, WatchSession> sessions = new ConcurrentHashMap<>();

    /** Byte Buddy transformers per session (id -> transformer). */
    private final ConcurrentHashMap<String, ResettableClassFileTransformer> transformers = new ConcurrentHashMap<>();

    /** Shared map for Advice callback to find session by id. */
    private static final ConcurrentHashMap<String, WatchSession> ACTIVE_SESSIONS = new ConcurrentHashMap<>();

    /** Scheduler for session expiration cleanup. */
    private final ScheduledExecutorService scheduler;

    private final OgnlService ognlService;

    public WatchService() {
        this(new OgnlService());
    }

    public WatchService(OgnlService ognlService) {
        this.ognlService = Objects.requireNonNull(ognlService, "ognlService");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "joltvm-watch-cleaner");
            t.setDaemon(true);
            return t;
        });
        // Periodically clean expired sessions
        scheduler.scheduleAtFixedRate(this::cleanExpiredSessions, 10, 10, TimeUnit.SECONDS);
        bridge = this;
    }

    /**
     * Starts a new watch session.
     *
     * @param classPattern  fully qualified class name
     * @param methodPattern method name ("*" for all methods)
     * @param conditionExpr optional OGNL condition (may be null)
     * @param maxRecords    max records to keep (default 1000)
     * @param durationMs    session duration in ms (default 60s, max 5min)
     * @return the created session's summary map
     * @throws IllegalStateException if max concurrent watches reached or instrumentation unavailable
     * @throws IllegalArgumentException if classPattern blank or conditionExpr invalid
     */
    public Map<String, Object> startWatch(String classPattern, String methodPattern,
                                           String conditionExpr, int maxRecords, long durationMs) {
        if (classPattern == null || classPattern.isBlank()) {
            throw new IllegalArgumentException("classPattern is required");
        }
        if (sessions.size() >= MAX_CONCURRENT_WATCHES) {
            throw new IllegalStateException("Maximum concurrent watches reached (" + MAX_CONCURRENT_WATCHES
                    + "). Stop an existing watch first.");
        }

        long effectiveDuration = durationMs > 0 ? Math.min(durationMs, MAX_DURATION_MS) : DEFAULT_DURATION_MS;
        String effectiveMethod = (methodPattern == null || methodPattern.isBlank()) ? "*" : methodPattern;
        String effectiveCondition = (conditionExpr == null || conditionExpr.isBlank())
                ? null : conditionExpr.trim();

        // Validate + precompile before touching instrumentation
        Object compiledCondition = null;
        if (effectiveCondition != null) {
            try {
                compiledCondition = ognlService.compileCondition(effectiveCondition);
            } catch (SecurityException e) {
                throw new IllegalArgumentException("Invalid conditionExpr: " + e.getMessage(), e);
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid conditionExpr: " + e.getMessage(), e);
            }
        }

        if (!InstrumentationHolder.isAvailable()) {
            throw new IllegalStateException("Instrumentation not available. Agent may not be loaded.");
        }

        WatchSession session = new WatchSession(classPattern, effectiveMethod,
                effectiveCondition, maxRecords, effectiveDuration);
        if (compiledCondition != null) {
            session.setCompiledCondition(compiledCondition);
        }

        // Register for Advice callback
        ACTIVE_SESSIONS.put(session.getId(), session);
        sessions.put(session.getId(), session);

        try {
            // Install Byte Buddy Advice
            Instrumentation inst = InstrumentationHolder.get();

            ElementMatcher<? super TypeDescription> typeMatcher = ElementMatchers.named(classPattern);
            ElementMatcher<? super MethodDescription> methodMatcher;
            if ("*".equals(effectiveMethod)) {
                methodMatcher = ElementMatchers.isMethod()
                        .and(ElementMatchers.not(ElementMatchers.isConstructor()))
                        .and(ElementMatchers.not(ElementMatchers.isTypeInitializer()));
            } else {
                methodMatcher = ElementMatchers.named(effectiveMethod);
            }

            ResettableClassFileTransformer transformer = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .ignore(ElementMatchers.nameStartsWith("com.joltvm."))
                    .type(typeMatcher)
                    .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                            builder.visit(Advice.to(WatchAdvice.class).on(methodMatcher)))
                    .installOn(inst);

            transformers.put(session.getId(), transformer);
            LOG.info("Watch started: " + session.getId() + " on " + classPattern + "#" + effectiveMethod);

        } catch (Exception e) {
            sessions.remove(session.getId());
            ACTIVE_SESSIONS.remove(session.getId());
            throw new IllegalStateException("Failed to install watch: " + e.getMessage(), e);
        }

        return session.toSummaryMap();
    }

    /**
     * Stops a watch session and removes its Advice transformer.
     *
     * @param sessionId the session ID
     * @return the session's final summary map, or null if not found
     */
    public Map<String, Object> stopWatch(String sessionId) {
        WatchSession session = sessions.get(sessionId);
        if (session == null) return null;

        session.stop();
        removeTransformer(sessionId);
        ACTIVE_SESSIONS.remove(sessionId);

        LOG.info("Watch stopped: " + sessionId);
        return session.toSummaryMap();
    }

    /**
     * Removes a watch session entirely.
     *
     * @param sessionId the session ID
     * @return true if removed
     */
    public boolean deleteWatch(String sessionId) {
        WatchSession session = sessions.remove(sessionId);
        if (session == null) return false;

        session.stop();
        removeTransformer(sessionId);
        ACTIVE_SESSIONS.remove(sessionId);
        return true;
    }

    /**
     * Returns records from a watch session.
     *
     * @param sessionId the session ID
     * @param since     start index (0-based)
     * @return map with records, count, session info
     */
    public Map<String, Object> getRecords(String sessionId, int since) {
        WatchSession session = sessions.get(sessionId);
        if (session == null) return null;

        List<WatchRecord> records = since > 0 ? session.getRecordsSince(since) : session.getRecords();
        List<Map<String, Object>> recordMaps = new ArrayList<>();
        for (WatchRecord r : records) {
            recordMaps.add(r.toMap());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("records", recordMaps);
        result.put("count", recordMaps.size());
        result.put("session", session.toSummaryMap());
        return result;
    }

    /**
     * Lists all watch sessions (active and stopped).
     *
     * @return map with sessions list and count
     */
    public Map<String, Object> listWatches() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (WatchSession session : sessions.values()) {
            list.add(session.toSummaryMap());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("watches", list);
        result.put("count", list.size());
        result.put("maxConcurrent", MAX_CONCURRENT_WATCHES);
        return result;
    }

    /**
     * Used by {@link WatchAdvice} to record an invocation.
     * Condition evaluation and stringification happen here on the agent side.
     */
    static void recordInvocation(String className, String methodName,
                                  Object[] args, Object returnValue, Throwable thrown,
                                  Object target, Class<?> clazz, long durationNanos) {
        WatchService svc = bridge;
        if (svc == null) {
            return;
        }
        svc.doRecordInvocation(className, methodName, args, returnValue, thrown,
                target, clazz, durationNanos);
    }

    private void doRecordInvocation(String className, String methodName,
                                     Object[] args, Object returnValue, Throwable thrown,
                                     Object target, Class<?> clazz, long durationNanos) {
        for (WatchSession session : ACTIVE_SESSIONS.values()) {
            if (!session.isActive()) continue;
            if (!matchesSession(session, className, methodName)) continue;

            session.incrementTotalSeen();

            if (session.getCompiledCondition() != null) {
                Map<String, Object> ctx = buildConditionContext(
                        args, returnValue, thrown, target, clazz, durationNanos);
                if (!ognlService.evaluateCondition(session.getCompiledCondition(), ctx)) {
                    continue;
                }
            }

            String[] argStrings = stringifyArgs(args);
            String returnStr = (thrown == null && returnValue != null)
                    ? truncate(safeToString(returnValue), TRUNCATE_LEN) : null;
            String exType = thrown != null ? thrown.getClass().getName() : null;
            String exMsg = thrown != null ? truncate(safeToString(thrown.getMessage()), TRUNCATE_LEN) : null;

            WatchRecord record = new WatchRecord(
                    Instant.now(), className, methodName,
                    Thread.currentThread().getName(), Thread.currentThread().getId(),
                    argStrings, returnStr, exType, exMsg,
                    durationNanos, thrown != null ? "EXCEPTION" : "AFTER");
            session.addRecord(record);
        }
    }

    private static Map<String, Object> buildConditionContext(
            Object[] args, Object returnValue, Throwable thrown,
            Object target, Class<?> clazz, long durationNanos) {
        Map<String, Object> ctx = new HashMap<>(8);
        ctx.put("args", args != null ? args : new Object[0]);
        ctx.put("returnObj", returnValue);
        ctx.put("throwExp", thrown);
        ctx.put("cost", durationNanos);
        ctx.put("target", target);
        ctx.put("clazz", clazz);
        return ctx;
    }

    private static String[] stringifyArgs(Object[] arguments) {
        if (arguments == null) {
            return null;
        }
        String[] argStrings = new String[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            argStrings[i] = truncate(safeToString(arguments[i]), TRUNCATE_LEN);
        }
        return argStrings;
    }

    private static String safeToString(Object obj) {
        if (obj == null) return "null";
        try {
            return obj.toString();
        } catch (Exception e) {
            return obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }

    private static boolean matchesSession(WatchSession session, String className, String methodName) {
        if (className == null || !session.getClassPattern().equals(className)) return false;
        String pattern = session.getMethodPattern();
        return "*".equals(pattern) || pattern.equals(methodName);
    }

    private void removeTransformer(String sessionId) {
        ResettableClassFileTransformer transformer = transformers.remove(sessionId);
        if (transformer != null) {
            try {
                Instrumentation inst = InstrumentationHolder.get();
                transformer.reset(inst, AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to reset watch transformer: " + sessionId, e);
            }
        }
    }

    private void cleanExpiredSessions() {
        for (Map.Entry<String, WatchSession> entry : sessions.entrySet()) {
            if (entry.getValue().isExpired() && entry.getValue().isActive()) {
                stopWatch(entry.getKey());
            }
        }
    }

    /** Visible for testing — registers a session without installing Advice. */
    void registerSessionForTest(WatchSession session) {
        ACTIVE_SESSIONS.put(session.getId(), session);
        sessions.put(session.getId(), session);
    }

    /** Shuts down the service. */
    public void shutdown() {
        scheduler.shutdownNow();
        for (String id : new ArrayList<>(sessions.keySet())) {
            deleteWatch(id);
        }
        if (bridge == this) {
            bridge = null;
        }
    }

    /** Visible for testing. */
    int activeSessionCount() {
        return (int) sessions.values().stream().filter(WatchSession::isActive).count();
    }
}
