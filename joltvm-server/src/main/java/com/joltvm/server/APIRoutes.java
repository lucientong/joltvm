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

import com.joltvm.server.handler.ClassDetailHandler;
import com.joltvm.server.handler.ClassListHandler;
import com.joltvm.server.handler.ClassSourceHandler;
import com.joltvm.server.handler.CompileHandler;
import com.joltvm.server.handler.HealthHandler;
import com.joltvm.server.handler.HotSwapHandler;
import com.joltvm.server.handler.HotSwapHistoryHandler;
import com.joltvm.server.handler.RollbackHandler;
import com.joltvm.server.handler.TraceFlameGraphHandler;
import com.joltvm.server.handler.TraceHandler;
import com.joltvm.server.handler.TraceListHandler;
import com.joltvm.server.handler.TraceStatusHandler;
import com.joltvm.server.hotswap.HotSwapService;
import com.joltvm.server.tracing.MethodTraceService;
import io.netty.handler.codec.http.HttpMethod;

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
    static final int ROUTE_COUNT = 13;

    private APIRoutes() {
        // Utility class — no instantiation
    }

    /**
     * Registers all API endpoints on the given router.
     *
     * <p>Creates shared service instances and wires them into the appropriate handlers:
     * <ul>
     *   <li>{@link HotSwapService} — shared by HotSwapHandler, RollbackHandler, HotSwapHistoryHandler</li>
     *   <li>{@link MethodTraceService} — shared by TraceHandler, TraceListHandler,
     *       TraceFlameGraphHandler, TraceStatusHandler</li>
     * </ul>
     *
     * @param router the HTTP router to register routes on
     */
    public static void registerAll(HttpRouter router) {
        // Shared service singletons
        HotSwapService hotSwapService = new HotSwapService();
        MethodTraceService traceService = new MethodTraceService();

        router.addRoute(HttpMethod.GET, "/api/health", new HealthHandler());

        router.addRoute(HttpMethod.GET, "/api/classes", new ClassListHandler());
        router.addRoute(HttpMethod.GET, "/api/classes/{className}", new ClassDetailHandler());
        router.addRoute(HttpMethod.GET, "/api/classes/{className}/source", new ClassSourceHandler());

        router.addRoute(HttpMethod.POST, "/api/compile", new CompileHandler());
        router.addRoute(HttpMethod.POST, "/api/hotswap", new HotSwapHandler(hotSwapService));
        router.addRoute(HttpMethod.POST, "/api/rollback", new RollbackHandler(hotSwapService));
        router.addRoute(HttpMethod.GET, "/api/hotswap/history", new HotSwapHistoryHandler(hotSwapService));

        router.addRoute(HttpMethod.POST, "/api/trace/start", new TraceHandler(traceService));
        router.addRoute(HttpMethod.POST, "/api/trace/stop", new TraceHandler(traceService));
        router.addRoute(HttpMethod.GET, "/api/trace/records", new TraceListHandler(traceService));
        router.addRoute(HttpMethod.GET, "/api/trace/flamegraph", new TraceFlameGraphHandler(traceService));
        router.addRoute(HttpMethod.GET, "/api/trace/status", new TraceStatusHandler(traceService));

        LOG.info("Registered " + ROUTE_COUNT + " API routes");
    }
}
