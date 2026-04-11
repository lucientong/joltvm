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
 * Unit tests for {@link DependencyChainHandler}.
 */
@DisplayName("DependencyChainHandler")
class DependencyChainHandlerTest {

    // ── Spring not detected ──────────────────────────────────────────

    @Nested
    @DisplayName("When Spring not detected")
    class SpringNotDetected {

        private final SpringContextService springService = new SpringContextService();
        private final DependencyChainHandler handler = new DependencyChainHandler(springService);

        @Test
        @DisplayName("returns 503 when Spring not detected")
        void returnsServiceUnavailable() {
            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies/myBean");
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
                    "/api/spring/dependencies/testBean");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "testBean"));

            assertTrue(response.headers().get("Content-Type").contains("application/json"));

            response.release();
            request.release();
        }
    }

    // ── Spring detected ──────────────────────────────────────────────

    @Nested
    @DisplayName("When Spring detected")
    class SpringDetected {

        @Test
        @DisplayName("returns 400 when bean name is missing")
        void returnsBadRequestWhenBeanNameMissing() {
            StubSpringContextService stub = new StubSpringContextService(true);
            DependencyChainHandler handler = new DependencyChainHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies/");
            FullHttpResponse response = handler.handle(request, Map.of());

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
            DependencyChainHandler handler = new DependencyChainHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies/nonExistent");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "nonExistent"));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Bean not found: nonExistent"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns dependency chain for a Controller→Service→Repository chain")
        void returnsDependencyChain() {
            // Build a Controller → Service → Repository chain
            Map<String, Object> repoNode = new LinkedHashMap<>();
            repoNode.put("beanName", "userRepository");
            repoNode.put("className", "com.example.UserRepository");
            repoNode.put("stereotypes", List.of("Repository"));
            repoNode.put("dependencies", List.of());

            Map<String, Object> serviceNode = new LinkedHashMap<>();
            serviceNode.put("beanName", "userService");
            serviceNode.put("className", "com.example.UserService");
            serviceNode.put("stereotypes", List.of("Service"));
            serviceNode.put("dependencies", List.of(repoNode));

            Map<String, Object> controllerChain = new LinkedHashMap<>();
            controllerChain.put("beanName", "userController");
            controllerChain.put("className", "com.example.UserController");
            controllerChain.put("stereotypes", List.of("RestController", "Controller"));
            controllerChain.put("dependencies", List.of(serviceNode));

            StubSpringContextService stub = new StubSpringContextService(true)
                    .addDependencyChain("userController", controllerChain);
            DependencyChainHandler handler = new DependencyChainHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies/userController");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "userController"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"beanName\":\"userController\""));
            assertTrue(body.contains("\"beanName\":\"userService\""));
            assertTrue(body.contains("\"beanName\":\"userRepository\""));
            assertTrue(body.contains("RestController"));
            assertTrue(body.contains("Service"));
            assertTrue(body.contains("Repository"));

            response.release();
            request.release();
        }

        @Test
        @DisplayName("returns chain with circular dependency marker")
        void handlesCircularDependency() {
            Map<String, Object> circularNode = new LinkedHashMap<>();
            circularNode.put("beanName", "serviceA");
            circularNode.put("circular", true);

            Map<String, Object> serviceBNode = new LinkedHashMap<>();
            serviceBNode.put("beanName", "serviceB");
            serviceBNode.put("className", "com.example.ServiceB");
            serviceBNode.put("stereotypes", List.of("Service"));
            serviceBNode.put("dependencies", List.of(circularNode));

            Map<String, Object> serviceAChain = new LinkedHashMap<>();
            serviceAChain.put("beanName", "serviceA");
            serviceAChain.put("className", "com.example.ServiceA");
            serviceAChain.put("stereotypes", List.of("Service"));
            serviceAChain.put("dependencies", List.of(serviceBNode));

            StubSpringContextService stub = new StubSpringContextService(true)
                    .addDependencyChain("serviceA", serviceAChain);
            DependencyChainHandler handler = new DependencyChainHandler(stub);

            FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                    "/api/spring/dependencies/serviceA");
            FullHttpResponse response = handler.handle(request, Map.of("beanName", "serviceA"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("\"circular\":true"));

            response.release();
            request.release();
        }
    }
}
