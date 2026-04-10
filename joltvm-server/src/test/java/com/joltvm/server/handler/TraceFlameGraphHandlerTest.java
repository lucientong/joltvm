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

import com.joltvm.server.tracing.FlameGraphCollector;
import com.joltvm.server.tracing.MethodTraceService;
import com.joltvm.server.tracing.TraceRecord;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TraceFlameGraphHandler")
class TraceFlameGraphHandlerTest {

    private FlameGraphCollector collector;
    private TraceFlameGraphHandler handler;

    @BeforeEach
    void setUp() {
        collector = new FlameGraphCollector();
        MethodTraceService traceService = new MethodTraceService(collector);
        handler = new TraceFlameGraphHandler(traceService);
    }

    @Test
    @DisplayName("returns empty flame graph when no data")
    void returnsEmptyFlameGraph() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/flamegraph");
        FullHttpResponse response = handler.handle(request, Map.of());
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"name\":\"root\""));
        assertTrue(body.contains("\"value\":0"));
        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns flame graph from records")
    void returnsFlameGraphFromRecords() {
        collector.addRecord(new TraceRecord("id1", "com.example.A", "m1",
                List.of(), List.of(), null, null, null, 2_000_000L,
                "main", 1L, Instant.now(), 0));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/flamegraph");
        FullHttpResponse response = handler.handle(request, Map.of());
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("com.example.A#m1"));
        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns flame graph from samples")
    void returnsFlameGraphFromSamples() {
        collector.addStackSample(new StackTraceElement[]{
                new StackTraceElement("com.example.X", "leaf", "X.java", 1),
                new StackTraceElement("com.example.Y", "root", "Y.java", 1)
        });
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/flamegraph");
        FullHttpResponse response = handler.handle(request, Map.of());
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("com.example.Y#root"));
        response.release();
        request.release();
    }
}
