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

import com.joltvm.server.HttpResponseHelper;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CompileHandler}.
 */
@DisplayName("CompileHandler")
class CompileHandlerTest {

    private CompileHandler handler;

    @BeforeEach
    void setUp() {
        handler = new CompileHandler();
    }

    @Test
    @DisplayName("returns 400 for empty body")
    void returns400ForEmptyBody() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                "/api/compile");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Request body is required"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 for invalid JSON")
    void returns400ForInvalidJson() {
        FullHttpRequest request = createPostRequest("/api/compile", "not json");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Invalid JSON"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 for missing className")
    void returns400ForMissingClassName() {
        String json = HttpResponseHelper.gson().toJson(Map.of("sourceCode", "public class A {}"));
        FullHttpRequest request = createPostRequest("/api/compile", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("className"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 for missing sourceCode")
    void returns400ForMissingSourceCode() {
        String json = HttpResponseHelper.gson().toJson(Map.of("className", "com.test.A"));
        FullHttpRequest request = createPostRequest("/api/compile", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("sourceCode"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("compiles valid source successfully")
    void compilesValidSourceSuccessfully() {
        String sourceCode = """
                package com.test;
                public class HelloWorld {
                    public String greet() { return "Hello"; }
                }
                """;
        String json = HttpResponseHelper.gson().toJson(
                Map.of("className", "com.test.HelloWorld", "sourceCode", sourceCode));
        FullHttpRequest request = createPostRequest("/api/compile", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("Compilation successful"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 422 for compilation errors")
    void returns422ForCompilationErrors() {
        String sourceCode = """
                package com.test;
                public class Broken {
                    public void bad() { int x = }
                }
                """;
        String json = HttpResponseHelper.gson().toJson(
                Map.of("className", "com.test.Broken", "sourceCode", sourceCode));
        FullHttpRequest request = createPostRequest("/api/compile", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.UNPROCESSABLE_ENTITY, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":false"));
        assertTrue(body.contains("diagnostics"));

        response.release();
        request.release();
    }

    private FullHttpRequest createPostRequest(String uri, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri,
                Unpooled.wrappedBuffer(bytes));
    }
}
