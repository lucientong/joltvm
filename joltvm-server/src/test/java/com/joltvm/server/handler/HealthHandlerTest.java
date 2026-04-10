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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HealthHandler}.
 */
class HealthHandlerTest {

    private final HealthHandler handler = new HealthHandler();

    @Test
    @DisplayName("health endpoint returns 200 with status UP")
    void healthReturnsUp() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/health");

        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"status\":\"UP\""));
        assertTrue(body.contains("\"name\":\"JoltVM\""));
        assertTrue(body.contains("\"jvm\""));
        assertTrue(body.contains("\"memory\""));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("health response contains JVM info")
    void healthContainsJvmInfo() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/health");

        FullHttpResponse response = handler.handle(request, Map.of());
        String body = response.content().toString(StandardCharsets.UTF_8);

        assertTrue(body.contains("\"pid\""));
        assertTrue(body.contains("\"uptimeMs\""));
        assertTrue(body.contains("\"totalMb\""));

        response.release();
        request.release();
    }
}
