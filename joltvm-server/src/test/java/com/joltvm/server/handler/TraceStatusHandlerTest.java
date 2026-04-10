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

import com.joltvm.server.tracing.MethodTraceService;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TraceStatusHandler")
class TraceStatusHandlerTest {

    private MethodTraceService traceService;
    private TraceStatusHandler handler;

    @BeforeEach
    void setUp() {
        traceService = new MethodTraceService();
        handler = new TraceStatusHandler(traceService);
    }

    @Test
    @DisplayName("returns status when idle")
    void returnsIdleStatus() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/status");
        FullHttpResponse response = handler.handle(request, Map.of());
        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"tracing\":false"));
        assertTrue(body.contains("\"sampling\":false"));
        assertTrue(body.contains("\"recordCount\":0"));
        response.release();
        request.release();
        traceService.reset();
    }

    @Test
    @DisplayName("reflects sampling state")
    void reflectsSamplingState() {
        traceService.startSampling(50, 5);
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/trace/status");
        FullHttpResponse response = handler.handle(request, Map.of());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"sampling\":true"));
        response.release();
        request.release();
        traceService.reset();
    }
}
