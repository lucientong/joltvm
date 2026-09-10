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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.joltvm.server.handler.OpenApiHandler;
import com.joltvm.server.security.Role;
import com.joltvm.server.security.RoutePermissions;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        assertNotNull(router.match(HttpMethod.GET, "/api/openapi.json"));
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
    @DisplayName("Spring Boot awareness endpoints are registered")
    void springEndpoints() {
        assertNotNull(router.match(HttpMethod.GET, "/api/spring/beans"));
        assertNotNull(router.match(HttpMethod.GET, "/api/spring/beans/myBean"));
        assertNotNull(router.match(HttpMethod.GET, "/api/spring/mappings"));
        assertNotNull(router.match(HttpMethod.GET, "/api/spring/dependencies"));
        assertNotNull(router.match(HttpMethod.GET, "/api/spring/dependencies/myBean"));
    }

    @Test
    @DisplayName("unregistered paths return null")
    void unregisteredPathsReturnNull() {
        assertNull(router.match(HttpMethod.GET, "/api/unknown"));
        assertNull(router.match(HttpMethod.DELETE, "/api/health"));
    }

    @Test
    @DisplayName("OpenAPI contract covers every static route exactly once")
    void openApiContractMatchesRegisteredRoutes() throws Exception {
        Set<String> registered = new HashSet<>();
        for (HttpRouter.Route route : router.getRoutes()) {
            registered.add(route.getMethod().name().toLowerCase() + " " + route.getPattern());
        }

        Set<String> documented = new HashSet<>();
        Set<String> operationIds = new HashSet<>();
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream(OpenApiHandler.RESOURCE_PATH)) {
            assertNotNull(input, "Bundled OpenAPI document must exist");
            JsonObject paths = JsonParser.parseReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("paths");
            for (Map.Entry<String, JsonElement> pathEntry : paths.entrySet()) {
                for (Map.Entry<String, JsonElement> operationEntry
                        : pathEntry.getValue().getAsJsonObject().entrySet()) {
                    String method = operationEntry.getKey();
                    if (!Set.of("get", "post", "put", "delete", "patch").contains(method)) {
                        continue;
                    }
                    documented.add(method + " " + pathEntry.getKey());
                    JsonObject operation = operationEntry.getValue().getAsJsonObject();
                    String operationId = operation.get("operationId").getAsString();
                    assertTrue(operationIds.add(operationId),
                            "Duplicate OpenAPI operationId: " + operationId);

                    Role requiredRole = RoutePermissions.getRequiredRole(
                            method.toUpperCase(), pathEntry.getKey());
                    String expectedRole = requiredRole == null ? "PUBLIC" : requiredRole.name();
                    assertEquals(expectedRole, operation.get("x-required-role").getAsString(),
                            "Incorrect documented role for "
                                    + method.toUpperCase() + " " + pathEntry.getKey());
                }
            }
        }

        assertEquals(registered, documented,
                "OpenAPI paths and APIRoutes registrations must stay synchronized");
    }
}
