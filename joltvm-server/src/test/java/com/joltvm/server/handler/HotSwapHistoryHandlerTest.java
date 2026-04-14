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

import com.joltvm.server.hotswap.HotSwapService;
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

/**
 * Tests for {@link HotSwapHistoryHandler}.
 */
@DisplayName("HotSwapHistoryHandler")
class HotSwapHistoryHandlerTest {

    private HotSwapHistoryHandler handler;
    private HotSwapService hotSwapService;

    @BeforeEach
    void setUp() {
        hotSwapService = new HotSwapService();
        handler = new HotSwapHistoryHandler(hotSwapService);
    }

    @Test
    @DisplayName("returns empty history initially")
    void returnsEmptyHistoryInitially() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/hotswap/history");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"records\":[]"));
        assertTrue(body.contains("\"total\":0"));
        assertTrue(body.contains("\"rollbackable\":[]"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("respects limit parameter")
    void respectsLimitParameter() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/hotswap/history?limit=5");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"records\""));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("handles invalid limit gracefully")
    void handlesInvalidLimitGracefully() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/hotswap/history?limit=abc");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());

        response.release();
        request.release();
    }
}
