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
 * Unit tests for {@link BeanListHandler}.
 */
@DisplayName("BeanListHandler")
class BeanListHandlerTest {

    // ── Spring not detected ──────────────────────────────────────────

    @Nested
    @DisplayName("When Spring not detected")
    class SpringNotDetected {

        private final SpringContextService springService = new SpringContextService();
        private final BeanListHandler handler = new BeanListHandler(springService);

        @Test
        @DisplayName("returns springDetected=false with empty beans list")
        void returnsSpringNotDetected() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":false"));
            assertTrue(body.contains("\"total\":0"));
            assertTrue(body.contains("\"beans\":[]"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("response includes 'not detected' message")
        void includesNotDetectedMessage() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans");
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
                    "/api/spring/beans");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertTrue(response.headers().get("Content-Type").contains("application/json"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("includes CORS headers")
        void includesCorsHeaders() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals("*", response.headers().get("Access-Control-Allow-Origin"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("handles query parameters gracefully when Spring not detected")
        void handlesQueryParamsGracefully() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?package=com.example&search=user&stereotype=Service&page=1&size=10");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":false"));
            assertTrue(body.contains("\"total\":0"));

            response.release();
            request.release();
        }
    }

    // ── Spring detected with data ────────────────────────────────────

    @Nested
    @DisplayName("When Spring detected with beans")
    class SpringDetected {

        private StubSpringContextService stubService;
        private BeanListHandler handler;

        private StubSpringContextService createServiceWithSampleBeans() {
            return new StubSpringContextService(true)
                    .addBean("userService", "com.example.service.UserService", "UserService",
                            "com.example.service", "singleton", List.of("Service"), true)
                    .addBean("orderService", "com.example.service.OrderService", "OrderService",
                            "com.example.service", "singleton", List.of("Service"), true)
                    .addBean("userController", "com.example.controller.UserController", "UserController",
                            "com.example.controller", "singleton", List.of("RestController", "Controller"), true)
                    .addBean("orderRepository", "com.example.repo.OrderRepository", "OrderRepository",
                            "com.example.repo", "singleton", List.of("Repository"), true)
                    .addBean("appConfig", "com.example.config.AppConfig", "AppConfig",
                            "com.example.config", "singleton", List.of("Configuration"), true);
        }

        @Test
        @DisplayName("returns all beans with springDetected=true")
        void returnsAllBeans() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":true"));
            assertTrue(body.contains("\"total\":5"));
            assertTrue(body.contains("userService"));
            assertTrue(body.contains("orderService"));
            assertTrue(body.contains("userController"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("filters by package prefix")
        void filtersByPackage() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?package=com.example.service");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":2"));
            assertTrue(body.contains("userService"));
            assertTrue(body.contains("orderService"));
            assertFalse(body.contains("userController"));
            assertFalse(body.contains("orderRepository"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("filters by search keyword (case-insensitive)")
        void filtersBySearch() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?search=user");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":2"));
            assertTrue(body.contains("userService"));
            assertTrue(body.contains("userController"));
            assertFalse(body.contains("orderService"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("filters by stereotype")
        void filtersByStereotype() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?stereotype=Service");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":2"));
            assertTrue(body.contains("userService"));
            assertTrue(body.contains("orderService"));
            assertFalse(body.contains("userController"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("paginates results correctly")
        void paginatesResults() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            // Page 1, size 2
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?page=1&size=2");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":5"));
            assertTrue(body.contains("\"page\":1"));
            assertTrue(body.contains("\"size\":2"));

            response.release();
            request.release();

            // Page 2, size 2
            FullHttpRequest request2 = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?page=2&size=2");
            FullHttpResponse response2 = handler.handle(request2, Map.of());

            String body2 = response2.content().toString(StandardCharsets.UTF_8);
            assertTrue(body2.contains("\"total\":5"));
            assertTrue(body2.contains("\"page\":2"));

            response2.release();
            request2.release();
        }

        @Test
        @DisplayName("page beyond total returns empty beans list")
        void pageBeyondTotal() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?page=100&size=10");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":5"));
            assertTrue(body.contains("\"beans\":[]"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("combines multiple filters (package + stereotype)")
        void combinesMultipleFilters() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?package=com.example.controller&stereotype=Controller");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"total\":1"));
            assertTrue(body.contains("userController"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("handles invalid page/size gracefully")
        void handlesInvalidPageSize() {
            stubService = createServiceWithSampleBeans();
            handler = new BeanListHandler(stubService);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans?page=abc&size=xyz");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            // Should fall back to defaults (page=1, size=100)
            assertTrue(body.contains("\"page\":1"));
            assertTrue(body.contains("\"total\":5"));

            response.release();
            request.release();
        }
    }
}
