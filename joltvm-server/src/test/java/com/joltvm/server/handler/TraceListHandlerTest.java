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

@DisplayName("TraceListHandler")
class TraceListHandlerTest {

    private FlameGraphCollector collector;
    private MethodTraceService traceService;
    private TraceListHandler handler;

    @BeforeEach
    void setUp() {
        collector = new FlameGraphCollector();
        traceService = new MethodTraceService(collector);
        handler = new TraceListHandler(traceService);
    }

    @Test
    @DisplayName("returns empty records when no traces")
    void returnsEmptyWhenNoTraces() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/records");
        FullHttpResponse response = handler.handle(request, Map.of());
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"records\":[]"));
        assertTrue(body.contains("\"total\":0"));
        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns trace records with fields")
    void returnsTraceRecords() {
        collector.addRecord(new TraceRecord(
                "abc123", "com.example.Service", "handle",
                List.of("String"), List.of("arg1"),
                "OK", null, null, 5_000_000L,
                "main", 1L, Instant.parse("2026-04-11T00:00:00Z"), 0));
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/records");
        FullHttpResponse response = handler.handle(request, Map.of());
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"className\":\"com.example.Service\""));
        assertTrue(body.contains("\"total\":1"));
        response.release();
        request.release();
    }

    @Test
    @DisplayName("respects limit parameter")
    void respectsLimit() {
        for (int i = 0; i < 10; i++) {
            collector.addRecord(new TraceRecord("id" + i, "C" + i, "m",
                    List.of(), List.of(), null, null, null, 1000L,
                    "main", 1L, Instant.now(), 0));
        }
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/records?limit=3");
        FullHttpResponse response = handler.handle(request, Map.of());
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"total\":10"));
        response.release();
        request.release();
    }
}
