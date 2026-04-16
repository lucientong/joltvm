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

package com.joltvm.server;

import com.joltvm.server.handler.AuditExportHandler;
import com.joltvm.server.handler.AsyncProfilerFlameGraphHandler;
import com.joltvm.server.handler.AsyncProfilerStartHandler;
import com.joltvm.server.handler.AsyncProfilerStatusHandler;
import com.joltvm.server.handler.AsyncProfilerStopHandler;
import com.joltvm.server.handler.AuthStatusHandler;
import com.joltvm.server.handler.BeanDetailHandler;
import com.joltvm.server.handler.BeanListHandler;
import com.joltvm.server.handler.ClassDetailHandler;
import com.joltvm.server.handler.ClassLoaderClassesHandler;
import com.joltvm.server.handler.ClassLoaderConflictsHandler;
import com.joltvm.server.handler.ClassLoaderTreeHandler;
import com.joltvm.server.handler.ClassListHandler;
import com.joltvm.server.handler.ClassSourceHandler;
import com.joltvm.server.handler.ClasspathHandler;
import com.joltvm.server.handler.CompileHandler;
import com.joltvm.server.handler.DependencyChainHandler;
import com.joltvm.server.handler.DependencyGraphHandler;
import com.joltvm.server.handler.GcStatsHandler;
import com.joltvm.server.handler.HealthHandler;
import com.joltvm.server.handler.HotSwapHandler;
import com.joltvm.server.handler.HotSwapHistoryHandler;
import com.joltvm.server.handler.LoginHandler;
import com.joltvm.server.handler.LoggerListHandler;
import com.joltvm.server.handler.LoggerUpdateHandler;
import com.joltvm.server.handler.OgnlEvalHandler;
import com.joltvm.server.handler.RequestMappingHandler;
import com.joltvm.server.handler.RollbackHandler;
import com.joltvm.server.handler.SysEnvHandler;
import com.joltvm.server.handler.SysPropsHandler;
import com.joltvm.server.handler.ThreadDeadlockHandler;
import com.joltvm.server.handler.ThreadDetailHandler;
import com.joltvm.server.handler.ThreadDumpHandler;
import com.joltvm.server.handler.ThreadListHandler;
import com.joltvm.server.handler.ThreadTopHandler;
import com.joltvm.server.handler.TraceFlameGraphHandler;
import com.joltvm.server.handler.TraceHandler;
import com.joltvm.server.handler.TraceListHandler;
import com.joltvm.server.handler.TraceStatusHandler;
import com.joltvm.server.handler.WatchDeleteHandler;
import com.joltvm.server.handler.WatchListHandler;
import com.joltvm.server.handler.WatchRecordsHandler;
import com.joltvm.server.handler.WatchStartHandler;
import com.joltvm.server.handler.WatchStopHandler;
import com.joltvm.server.classloader.ClassLoaderService;
import com.joltvm.server.hotswap.HotSwapService;
import com.joltvm.server.jvm.JvmInfoService;
import com.joltvm.server.logger.LoggerService;
import com.joltvm.server.ognl.OgnlService;
import com.joltvm.server.profiler.AsyncProfilerService;
import com.joltvm.server.watch.WatchService;
import com.joltvm.server.security.AuditLogService;
import com.joltvm.server.security.SecurityConfig;
import com.joltvm.server.security.TokenService;
import com.joltvm.server.spring.SpringContextService;
import com.joltvm.server.thread.ThreadDiagnosticsService;
import com.joltvm.server.tracing.MethodTraceService;
import io.netty.handler.codec.http.HttpMethod;

import java.nio.file.Paths;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Central registry for all JoltVM REST API routes.
 *
 * <p>Registers all handler instances with the {@link HttpRouter} and manages
 * shared service singletons (e.g., {@link HotSwapService}, {@link MethodTraceService}).
 *
 * <p>Called reflectively by the agent:
 * <pre>
 *   APIRoutes.registerAll(server.getRouter());
 * </pre>
 *
 * @see HttpRouter
 * @see com.joltvm.agent.JoltVMAgent
 */
public final class APIRoutes {

    private static final Logger LOG = Logger.getLogger(APIRoutes.class.getName());

    /** Total number of registered API endpoints. */
    static final int ROUTE_COUNT = 45;

    /**
     * Immutable holder for shared service instances, ensuring atomic publication
     * of all service references via a single volatile write.
     */
    private record ServiceHolder(MethodTraceService traceService, AuditLogService auditLogService) {
    }

    private static volatile ServiceHolder services;

    private APIRoutes() {
        // Utility class — no instantiation
    }

    /**
     * Returns the shared {@link MethodTraceService} singleton.
     *
     * <p>Available after {@link #registerAll} has been called. Returns {@code null} if
     * routes have not been registered yet.
     *
     * @return the trace service, or {@code null}
     */
    public static MethodTraceService getTraceService() {
        ServiceHolder holder = services;
        return holder != null ? holder.traceService() : null;
    }

    /**
     * Returns the shared {@link AuditLogService} singleton.
     *
     * <p>Available after {@link #registerAll(HttpRouter, SecurityConfig, TokenService)}
     * has been called. Returns {@code null} if routes have not been registered yet.
     *
     * @return the audit log service, or {@code null}
     */
    public static AuditLogService getAuditLogService() {
        ServiceHolder holder = services;
        return holder != null ? holder.auditLogService() : null;
    }

    /**
     * Registers all API endpoints on the given router (security disabled).
     *
     * <p>Convenience overload that creates default {@link SecurityConfig} and
     * {@link TokenService} instances.
     *
     * @param router the HTTP router to register routes on
     */
    public static void registerAll(HttpRouter router) {
        registerAll(router, new SecurityConfig(), new TokenService(), Map.of());
    }

    /**
     * Registers all API endpoints with security support and no extra configuration.
     *
     * @param router         the HTTP router to register routes on
     * @param securityConfig the security configuration
     * @param tokenService   the token service
     */
    public static void registerAll(HttpRouter router, SecurityConfig securityConfig,
                                   TokenService tokenService) {
        registerAll(router, securityConfig, tokenService, Map.of());
    }

    /**
     * Registers all API endpoints on the given router with full configuration support.
     *
     * <p>Reads the following agent argument keys from {@code agentArgs}:
     * <ul>
     *   <li>{@code auditFile} — path for persistent JSON Lines audit log (optional)</li>
     * </ul>
     *
     * <p>Creates shared service instances and wires them into the appropriate handlers:
     * <ul>
     *   <li>{@link HotSwapService} — shared by HotSwapHandler, RollbackHandler, HotSwapHistoryHandler</li>
     *   <li>{@link MethodTraceService} — shared by TraceHandler, TraceListHandler,
     *       TraceFlameGraphHandler, TraceStatusHandler</li>
     *   <li>{@link SpringContextService} — shared by BeanListHandler, BeanDetailHandler,
     *       RequestMappingHandler, DependencyChainHandler, DependencyGraphHandler</li>
     *   <li>{@link AuditLogService} — shared by AuditExportHandler; persists to {@code auditFile}
     *       when provided, otherwise memory-only</li>
     *   <li>{@link SecurityConfig} + {@link TokenService} — shared by LoginHandler, AuthStatusHandler</li>
     * </ul>
     *
     * @param router         the HTTP router to register routes on
     * @param securityConfig the security configuration
     * @param tokenService   the token service
     * @param agentArgs      agent argument map (may be empty, never null)
     */
    public static void registerAll(HttpRouter router, SecurityConfig securityConfig,
                                   TokenService tokenService, Map<String, String> agentArgs) {
        // Shared service singletons
        HotSwapService hotSwapService = new HotSwapService();
        MethodTraceService traceService = new MethodTraceService();
        SpringContextService springService = new SpringContextService();
        ThreadDiagnosticsService threadService = new ThreadDiagnosticsService();
        JvmInfoService jvmInfoService = new JvmInfoService();
        ClassLoaderService classLoaderService = new ClassLoaderService();
        LoggerService loggerService = new LoggerService();
        OgnlService ognlService = new OgnlService();
        WatchService watchService = new WatchService();
        AsyncProfilerService asyncProfilerService = new AsyncProfilerService();

        String auditFilePath = agentArgs.get("auditFile");
        AuditLogService auditLogService = auditFilePath != null
                ? new AuditLogService(Paths.get(auditFilePath))
                : AuditLogService.createWithDefaultPath();

        // Atomic publication of all service references via immutable holder
        services = new ServiceHolder(traceService, auditLogService);

        router.addRoute(HttpMethod.GET, "/api/health", new HealthHandler());

        router.addRoute(HttpMethod.GET, "/api/classes", new ClassListHandler());
        router.addRoute(HttpMethod.GET, "/api/classes/{className}", new ClassDetailHandler());
        router.addRoute(HttpMethod.GET, "/api/classes/{className}/source", new ClassSourceHandler());

        router.addRoute(HttpMethod.POST, "/api/compile", new CompileHandler());
        router.addRoute(HttpMethod.POST, "/api/hotswap", new HotSwapHandler(hotSwapService, tokenService));
        router.addRoute(HttpMethod.POST, "/api/rollback", new RollbackHandler(hotSwapService, tokenService));
        router.addRoute(HttpMethod.GET, "/api/hotswap/history", new HotSwapHistoryHandler(hotSwapService));

        router.addRoute(HttpMethod.POST, "/api/trace/start", new TraceHandler(traceService));
        router.addRoute(HttpMethod.POST, "/api/trace/stop", new TraceHandler(traceService));
        router.addRoute(HttpMethod.GET, "/api/trace/records", new TraceListHandler(traceService));
        router.addRoute(HttpMethod.GET, "/api/trace/flamegraph", new TraceFlameGraphHandler(traceService));
        router.addRoute(HttpMethod.GET, "/api/trace/status", new TraceStatusHandler(traceService));

        router.addRoute(HttpMethod.GET, "/api/spring/beans", new BeanListHandler(springService));
        router.addRoute(HttpMethod.GET, "/api/spring/beans/{beanName}", new BeanDetailHandler(springService));
        router.addRoute(HttpMethod.GET, "/api/spring/mappings", new RequestMappingHandler(springService));
        router.addRoute(HttpMethod.GET, "/api/spring/dependencies", new DependencyGraphHandler(springService));
        router.addRoute(HttpMethod.GET, "/api/spring/dependencies/{beanName}", new DependencyChainHandler(springService));

        // Thread diagnostics endpoints
        router.addRoute(HttpMethod.GET, "/api/threads", new ThreadListHandler(threadService));
        router.addRoute(HttpMethod.GET, "/api/threads/top", new ThreadTopHandler(threadService));
        router.addRoute(HttpMethod.GET, "/api/threads/deadlocks", new ThreadDeadlockHandler(threadService));
        router.addRoute(HttpMethod.GET, "/api/threads/dump", new ThreadDumpHandler(threadService));
        router.addRoute(HttpMethod.GET, "/api/threads/{id}", new ThreadDetailHandler(threadService));

        // JVM info endpoints
        router.addRoute(HttpMethod.GET, "/api/jvm/gc", new GcStatsHandler(jvmInfoService));
        router.addRoute(HttpMethod.GET, "/api/jvm/sysprops", new SysPropsHandler(jvmInfoService));
        router.addRoute(HttpMethod.GET, "/api/jvm/sysenv", new SysEnvHandler(jvmInfoService));
        router.addRoute(HttpMethod.GET, "/api/jvm/classpath", new ClasspathHandler(jvmInfoService));

        // ClassLoader analysis endpoints
        router.addRoute(HttpMethod.GET, "/api/classloaders", new ClassLoaderTreeHandler(classLoaderService));
        router.addRoute(HttpMethod.GET, "/api/classloaders/conflicts", new ClassLoaderConflictsHandler(classLoaderService));
        router.addRoute(HttpMethod.GET, "/api/classloaders/{id}/classes", new ClassLoaderClassesHandler(classLoaderService));

        // Logger endpoints
        router.addRoute(HttpMethod.GET, "/api/loggers", new LoggerListHandler(loggerService));
        router.addRoute(HttpMethod.PUT, "/api/loggers/{name}", new LoggerUpdateHandler(loggerService));

        // OGNL expression engine
        router.addRoute(HttpMethod.POST, "/api/ognl/eval", new OgnlEvalHandler(ognlService));

        // Watch command endpoints
        router.addRoute(HttpMethod.POST, "/api/watch/start", new WatchStartHandler(watchService));
        router.addRoute(HttpMethod.GET, "/api/watch", new WatchListHandler(watchService));
        router.addRoute(HttpMethod.POST, "/api/watch/{id}/stop", new WatchStopHandler(watchService));
        router.addRoute(HttpMethod.GET, "/api/watch/{id}/records", new WatchRecordsHandler(watchService));
        router.addRoute(HttpMethod.DELETE, "/api/watch/{id}", new WatchDeleteHandler(watchService));

        // async-profiler endpoints
        router.addRoute(HttpMethod.GET, "/api/profiler/async/status", new AsyncProfilerStatusHandler(asyncProfilerService));
        router.addRoute(HttpMethod.POST, "/api/profiler/async/start", new AsyncProfilerStartHandler(asyncProfilerService));
        router.addRoute(HttpMethod.POST, "/api/profiler/async/stop", new AsyncProfilerStopHandler(asyncProfilerService));
        router.addRoute(HttpMethod.GET, "/api/profiler/async/flamegraph/{id}", new AsyncProfilerFlameGraphHandler(asyncProfilerService));

        // Security & audit endpoints
        router.addRoute(HttpMethod.POST, "/api/auth/login", new LoginHandler(securityConfig, tokenService));
        router.addRoute(HttpMethod.GET, "/api/auth/status", new AuthStatusHandler(securityConfig, tokenService));
        router.addRoute(HttpMethod.GET, "/api/audit/export", new AuditExportHandler(auditLogService));

        LOG.info("Registered " + ROUTE_COUNT + " API routes");
    }
}
