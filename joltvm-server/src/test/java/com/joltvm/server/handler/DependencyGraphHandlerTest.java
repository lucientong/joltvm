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
 * Unit tests for {@link DependencyGraphHandler}.
 */
@DisplayName("DependencyGraphHandler")
class DependencyGraphHandlerTest {

    // ── Spring not detected ──────────────────────────────────────────

    @Nested
    @DisplayName("When Spring not detected")
    class SpringNotDetected {

        private final SpringContextService springService = new SpringContextService();
        private final DependencyGraphHandler handler = new DependencyGraphHandler(springService);

        @Test
        @DisplayName("returns springDetected=false with empty graph")
        void returnsSpringNotDetected() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":false"));
            assertTrue(body.contains("\"total\":0"));
            assertTrue(body.contains("\"graph\":[]"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("includes Spring not detected message")
        void includesNotDetectedMessage() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Spring Framework not detected"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns JSON with CORS headers")
        void returnsJsonWithCors() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertTrue(response.headers().get("Content-Type").contains("application/json"));
            assertEquals("*", response.headers().get("Access-Control-Allow-Origin"));

            response.release();
            request.release();
        }
    }

    // ── Spring detected ──────────────────────────────────────────────

    @Nested
    @DisplayName("When Spring detected with dependency graph")
    class SpringDetected {

        @Test
        @DisplayName("returns full dependency graph")
        void returnsFullGraph() {
            Map<String, Object> dep1 = new LinkedHashMap<>();
            dep1.put("fieldName", "userService");
            dep1.put("fieldType", "com.example.UserService");
            dep1.put("beanName", "userService");
            dep1.put("stereotypes", List.of("Service"));

            Map<String, Object> controllerEntry = new LinkedHashMap<>();
            controllerEntry.put("beanName", "userController");
            controllerEntry.put("className", "com.example.UserController");
            controllerEntry.put("stereotypes", List.of("RestController"));
            controllerEntry.put("dependencies", List.of(dep1));

            Map<String, Object> dep2 = new LinkedHashMap<>();
            dep2.put("fieldName", "userRepo");
            dep2.put("fieldType", "com.example.UserRepository");
            dep2.put("beanName", "userRepository");
            dep2.put("stereotypes", List.of("Repository"));

            Map<String, Object> serviceEntry = new LinkedHashMap<>();
            serviceEntry.put("beanName", "userService");
            serviceEntry.put("className", "com.example.UserService");
            serviceEntry.put("stereotypes", List.of("Service"));
            serviceEntry.put("dependencies", List.of(dep2));

            StubSpringContextService stub = new StubSpringContextService(true)
                    .setDependencyGraph(List.of(controllerEntry, serviceEntry));
            DependencyGraphHandler handler = new DependencyGraphHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies");
            FullHttpResponse response = handler.handle(request, Map.of());

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":true"));
            assertTrue(body.contains("\"total\":2"));
            assertTrue(body.contains("userController"));
            assertTrue(body.contains("userService"));
            assertTrue(body.contains("RestController"));
            assertTrue(body.contains("Service"));
            assertTrue(body.contains("Repository"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns empty graph when no components have dependencies")
        void returnsEmptyGraphWhenNoDeps() {
            StubSpringContextService stub = new StubSpringContextService(true)
                    .setDependencyGraph(List.of());
            DependencyGraphHandler handler = new DependencyGraphHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies");
            FullHttpResponse response = handler.handle(request, Map.of());

            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"springDetected\":true"));
            assertTrue(body.contains("\"total\":0"));
            assertTrue(body.contains("\"graph\":[]"));

            response.release();
            request.release();
        }
    }
}
