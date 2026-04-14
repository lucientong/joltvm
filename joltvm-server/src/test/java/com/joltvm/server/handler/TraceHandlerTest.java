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
import com.joltvm.server.tracing.MethodTraceService;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TraceHandler}.
 */
@DisplayName("TraceHandler")
class TraceHandlerTest {

    private MethodTraceService traceService;
    private TraceHandler handler;

    @BeforeEach
    void setUp() {
        traceService = new MethodTraceService();
        handler = new TraceHandler(traceService);
    }

    @AfterEach
    void tearDown() {
        traceService.reset();
    }

    @Test
    @DisplayName("start returns 400 for empty body")
    void startReturns400ForEmptyBody() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/trace/start");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Request body is required"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("start returns 400 for invalid JSON")
    void startReturns400ForInvalidJson() {
        FullHttpRequest request = createPostRequest("/api/trace/start", "not json");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Invalid JSON"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("start returns 400 for missing type")
    void startReturns400ForMissingType() {
        String json = HttpResponseHelper.gson().toJson(Map.of("className", "com.example.A"));
        FullHttpRequest request = createPostRequest("/api/trace/start", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("type"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("start returns 400 for invalid type")
    void startReturns400ForInvalidType() {
        String json = HttpResponseHelper.gson().toJson(Map.of("type", "invalid"));
        FullHttpRequest request = createPostRequest("/api/trace/start", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Invalid type"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("start trace returns 400 for missing className")
    void startTraceReturns400ForMissingClassName() {
        String json = HttpResponseHelper.gson().toJson(Map.of("type", "trace"));
        FullHttpRequest request = createPostRequest("/api/trace/start", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("className"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("start trace returns 400 when instrumentation unavailable")
    void startTraceReturns400WhenNoInstrumentation() {
        String json = HttpResponseHelper.gson().toJson(
                Map.of("type", "trace", "className", "com.example.Test"));
        FullHttpRequest request = createPostRequest("/api/trace/start", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Instrumentation"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("start sampling returns 200")
    void startSamplingReturns200() {
        String json = HttpResponseHelper.gson().toJson(
                Map.of("type", "sample", "interval", 50, "duration", 5));
        FullHttpRequest request = createPostRequest("/api/trace/start", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("\"type\":\"sample\""));
        assertTrue(body.contains("Stack sampling started"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("stop returns 200 and stops all")
    void stopReturns200() {
        // Start sampling first
        traceService.startSampling(50, 5);

        FullHttpRequest request = createPostRequest("/api/trace/stop", "{}");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("\"stoppedSampling\":true"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("stop with empty body stops all")
    void stopWithEmptyBodyStopsAll() {
        FullHttpRequest request = createPostRequest("/api/trace/stop", "{}");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());

        response.release();
        request.release();
    }

    @Test
    @DisplayName("unknown URI returns 404")
    void unknownUriReturns404() {
        FullHttpRequest request = createPostRequest("/api/trace/unknown", "{}");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());

        response.release();
        request.release();
    }

    private FullHttpRequest createPostRequest(String uri, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri,
                Unpooled.wrappedBuffer(bytes));
    }
}
