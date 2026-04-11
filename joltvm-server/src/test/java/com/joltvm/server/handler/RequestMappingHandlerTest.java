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

package com.joltvm.server.handler;

import com.joltvm.server.spring.SpringContextService;
import com.joltvm.server.spring.StubSpringContextService;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link RequestMappingHandler}.
 */
@DisplayName("RequestMappingHandler")
class RequestMappingHandlerTest {

    // ── Spring not detected ──────────────────────────────────────────

    @Nested
    @DisplayName("When Spring not detected")
    class SpringNotDetected {

        private final SpringContextService springService = new SpringContextService();
        private final RequestMappingHandler handler = new RequestMappingHandler(springService);

        @Test
        @DisplayName("returns springDetected=false with empty mappings")
        void returnsSpringNotDetected() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":false"));
            assertTrue(body.contains("\"total\":0"));
            assertTrue(body.contains("\"mappings\":[]"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("response includes 'not detected' message")
        void includesNotDetectedMessage() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Spring Framework not detected"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns JSON content type")
        void returnsJsonContentType() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertTrue(response.headers().get("Content-Type").contains("application/json"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("includes CORS headers")
        void includesCorsHeaders() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals("*", response.headers().get("Access-Control-Allow-Origin"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("handles query parameters gracefully when Spring not detected")
        void handlesQueryParamsGracefully() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?search=users&method=GET");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":false"));
            assertTrue(body.contains("\"total\":0"));

            response.release();
            request.release();
        }
    }

    // ── Spring detected with mappings ────────────────────────────────

    @Nested
    @DisplayName("When Spring detected with mappings")
    class SpringDetected {

        private StubSpringContextService createServiceWithMappings() {
            return new StubSpringContextService(true)
                    .addMapping("/api/users", "GET", "userController",
                            "com.example.UserController", "listUsers",
                            "java.util.List", List.of())
                    .addMapping("/api/users/{id}", "GET", "userController",
                            "com.example.UserController", "getUserById",
                            "com.example.User", List.of("java.lang.Long"))
                    .addMapping("/api/users", "POST", "userController",
                            "com.example.UserController", "createUser",
                            "com.example.User", List.of("com.example.CreateUserRequest"))
                    .addMapping("/api/orders", "GET", "orderController",
                            "com.example.OrderController", "listOrders",
                            "java.util.List", List.of())
                    .addMapping("/api/orders/{id}", "DELETE", "orderController",
                            "com.example.OrderController", "deleteOrder",
                            "void", List.of("java.lang.Long"));
        }

        @Test
        @DisplayName("returns all mappings with springDetected=true")
        void returnsAllMappings() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":true"));
            assertTrue(body.contains("\"total\":5"));
            assertTrue(body.contains("/api/users"));
            assertTrue(body.contains("/api/orders"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("filters by HTTP method")
        void filtersByHttpMethod() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?method=GET");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":3"));
            assertTrue(body.contains("\"httpMethod\":\"GET\""));
            assertFalse(body.contains("\"httpMethod\":\"POST\""));
            assertFalse(body.contains("\"httpMethod\":\"DELETE\""));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("filters by search keyword on URL")
        void filtersBySearchOnUrl() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?search=orders");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":2"));
            assertTrue(body.contains("/api/orders"));
            assertFalse(body.contains("/api/users"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("filters by search keyword on method name")
        void filtersBySearchOnMethodName() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?search=createUser");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":1"));
            assertTrue(body.contains("createUser"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("filters by search keyword on class name")
        void filtersBySearchOnClassName() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?search=OrderController");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":2"));
            assertTrue(body.contains("OrderController"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("combines search and method filters")
        void combinesFilters() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?search=users&method=POST");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":1"));
            assertTrue(body.contains("createUser"));
            assertTrue(body.contains("\"httpMethod\":\"POST\""));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("no results when filters match nothing")
        void noResultsWhenFiltersMatchNothing() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?search=nonexistent");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":0"));
            assertTrue(body.contains("\"mappings\":[]"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("mapping response includes parameter types")
        void includesParameterTypes() {
            StubSpringContextService stub = createServiceWithMappings();
            RequestMappingHandler handler = new RequestMappingHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/mappings?search=getUserById");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("java.lang.Long"));
            assertTrue(body.contains("\"returnType\":\"com.example.User\""));

            response.release();
            request.release();
        }
    }
}
