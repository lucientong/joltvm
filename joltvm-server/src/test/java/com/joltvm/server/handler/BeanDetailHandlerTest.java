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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link BeanDetailHandler}.
 */
@DisplayName("BeanDetailHandler")
class BeanDetailHandlerTest {

    // ── Spring not detected ──────────────────────────────────────────

    @Nested
    @DisplayName("When Spring not detected")
    class SpringNotDetected {

        private final SpringContextService springService = new SpringContextService();
        private final BeanDetailHandler handler = new BeanDetailHandler(springService);

        @Test
        @DisplayName("returns 503 when Spring not detected")
        void returnsServiceUnavailable() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/myBean");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "myBean"));

            assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Spring Framework not detected"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns JSON content type")
        void returnsJsonContentType() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/testBean");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "testBean"));

            assertTrue(response.headers().get("Content-Type").contains("application/json"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("includes CORS headers in error response")
        void includesCorsHeaders() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/testBean");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "testBean"));

            assertEquals("*", response.headers().get("Access-Control-Allow-Origin"));

            response.release();
            request.release();
        }
    }

    // ── Spring detected ──────────────────────────────────────────────

    @Nested
    @DisplayName("When Spring detected")
    class SpringDetected {

        @Test
        @DisplayName("returns 400 when bean name is missing from path params")
        void returnsBadRequestWhenBeanNameMissing() {
            StubSpringContextService stub = new StubSpringContextService(true);
            BeanDetailHandler handler = new BeanDetailHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Bean name is required"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns 400 when bean name is blank")
        void returnsBadRequestWhenBeanNameBlank() {
            StubSpringContextService stub = new StubSpringContextService(true);
            BeanDetailHandler handler = new BeanDetailHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/ ");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "  "));

            assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Bean name is required"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns 404 when bean not found")
        void returnsNotFoundWhenBeanMissing() {
            StubSpringContextService stub = new StubSpringContextService(true);
            BeanDetailHandler handler = new BeanDetailHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/nonExistent");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "nonExistent"));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Bean not found: nonExistent"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns bean detail when bean exists")
        void returnsBeanDetail() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", "userService");
            detail.put("type", "com.example.UserService");
            detail.put("scope", "singleton");
            detail.put("stereotypes", List.of("Service"));
            detail.put("superclass", "java.lang.Object");
            detail.put("interfaces", List.of("com.example.IUserService"));
            detail.put("methods", List.of());

            StubSpringContextService stub = new StubSpringContextService(true)
                    .addBeanDetail("userService", detail);
            BeanDetailHandler handler = new BeanDetailHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/userService");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "userService"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"name\":\"userService\""));
            assertTrue(body.contains("\"type\":\"com.example.UserService\""));
            assertTrue(body.contains("\"scope\":\"singleton\""));
            assertTrue(body.contains("\"superclass\":\"java.lang.Object\""));
            assertTrue(body.contains("com.example.IUserService"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns bean detail with request mappings for controller bean")
        void returnsBeanDetailWithMappings() {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("name", "userController");
            detail.put("type", "com.example.UserController");
            detail.put("scope", "singleton");
            detail.put("stereotypes", List.of("RestController", "Controller"));
            Map<String, Object> mapping = new LinkedHashMap<>();
            mapping.put("url", "/api/users");
            mapping.put("httpMethod", "GET");
            detail.put("requestMappings", List.of(mapping));

            StubSpringContextService stub = new StubSpringContextService(true)
                    .addBeanDetail("userController", detail);
            BeanDetailHandler handler = new BeanDetailHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/beans/userController");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "userController"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"requestMappings\""));
            assertTrue(body.contains("/api/users"));
            assertTrue(body.contains("RestController"));

            response.release();
            request.release();
        }
    }
}
