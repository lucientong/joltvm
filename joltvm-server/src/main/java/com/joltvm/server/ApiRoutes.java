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
import com.joltvm.server.handler.HealthHandler;
import io.netty.handler.codec.http.HttpMethod;

import java.util.logging.Logger;

/**
 * Registers all API routes for the JoltVM server.
 *
 * <p>Call {@link #registerAll(HttpRouter)} during server initialization to set up
 * all REST API endpoints.
 *
 * <h3>Registered Endpoints:</h3>
 * <table>
 *   <tr><th>Method</th><th>Path</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/health</td><td>Health check</td></tr>
 *   <tr><td>GET</td><td>/api/classes</td><td>List loaded classes (paginated, filterable)</td></tr>
 *   <tr><td>GET</td><td>/api/classes/{className}</td><td>Class detail (fields, methods)</td></tr>
 *   <tr><td>GET</td><td>/api/classes/{className}/source</td><td>Decompiled source code</td></tr>
 * </table>
 */
public final class ApiRoutes {

    private static final Logger LOG = Logger.getLogger(ApiRoutes.class.getName());

    private ApiRoutes() {
        // Utility class
    }

    /**
     * Registers all API routes on the given router.
     *
     * @param router the HTTP router to register routes on
     */
    public static void registerAll(HttpRouter router) {
        // Health check
        router.addRoute(HttpMethod.GET, "/api/health", new HealthHandler());

        // Class inspection
        router.addRoute(HttpMethod.GET, "/api/classes", new ClassListHandler());
        router.addRoute(HttpMethod.GET, "/api/classes/{className}", new ClassDetailHandler());
        router.addRoute(HttpMethod.GET, "/api/classes/{className}/source", new ClassSourceHandler());

        LOG.info("Registered " + router.getRoutes().size() + " API routes");
    }
}
