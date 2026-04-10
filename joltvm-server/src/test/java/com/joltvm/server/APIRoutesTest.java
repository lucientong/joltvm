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

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link APIRoutes}.
 */
@DisplayName("APIRoutes")
class APIRoutesTest {

    private HttpRouter router;

    @BeforeEach
    void setUp() {
        router = new HttpRouter();
        APIRoutes.registerAll(router);
    }

    @Test
    @DisplayName("registers all expected routes")
    void registersAllRoutes() {
        List<HttpRouter.Route> routes = router.getRoutes();
        assertEquals(APIRoutes.ROUTE_COUNT, routes.size(),
                "Should register exactly " + APIRoutes.ROUTE_COUNT + " routes");
    }

    @Test
    @DisplayName("health endpoint is registered")
    void healthEndpoint() {
        assertNotNull(router.match(HttpMethod.GET, "/api/health"));
    }

    @Test
    @DisplayName("class browsing endpoints are registered")
    void classBrowsingEndpoints() {
        assertNotNull(router.match(HttpMethod.GET, "/api/classes"));
        assertNotNull(router.match(HttpMethod.GET, "/api/classes/com.example.MyClass"));
        assertNotNull(router.match(HttpMethod.GET, "/api/classes/com.example.MyClass/source"));
    }

    @Test
    @DisplayName("compile and hotswap endpoints are registered")
    void compileAndHotswapEndpoints() {
        assertNotNull(router.match(HttpMethod.POST, "/api/compile"));
        assertNotNull(router.match(HttpMethod.POST, "/api/hotswap"));
        assertNotNull(router.match(HttpMethod.POST, "/api/rollback"));
        assertNotNull(router.match(HttpMethod.GET, "/api/hotswap/history"));
    }

    @Test
    @DisplayName("trace endpoints are registered")
    void traceEndpoints() {
        assertNotNull(router.match(HttpMethod.POST, "/api/trace/start"));
        assertNotNull(router.match(HttpMethod.POST, "/api/trace/stop"));
        assertNotNull(router.match(HttpMethod.GET, "/api/trace/records"));
        assertNotNull(router.match(HttpMethod.GET, "/api/trace/flamegraph"));
        assertNotNull(router.match(HttpMethod.GET, "/api/trace/status"));
    }

    @Test
    @DisplayName("unregistered paths return null")
    void unregisteredPathsReturnNull() {
        assertNull(router.match(HttpMethod.GET, "/api/unknown"));
        assertNull(router.match(HttpMethod.DELETE, "/api/health"));
    }
}
