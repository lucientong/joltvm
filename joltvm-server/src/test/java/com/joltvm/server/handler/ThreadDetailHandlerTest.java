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

import com.joltvm.server.thread.ThreadDiagnosticsService;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ThreadDetailHandlerTest {

    private ThreadDetailHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ThreadDetailHandler(new ThreadDiagnosticsService());
    }

    @Test
    void returnsDetailForExistingThread() {
        long mainThreadId = Thread.currentThread().getId();
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/threads/" + mainThreadId);
        FullHttpResponse response = handler.handle(request, Map.of("id", String.valueOf(mainThreadId)));

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"stackTrace\""));
    }

    @Test
    void returns404ForNonExistingThread() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/threads/-99999");
        FullHttpResponse response = handler.handle(request, Map.of("id", "-99999"));

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
    }

    @Test
    void returns400ForInvalidId() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/threads/abc");
        FullHttpResponse response = handler.handle(request, Map.of("id", "abc"));

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }

    @Test
    void returns400ForMissingId() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/threads/");
        FullHttpResponse response = handler.handle(request, Map.of("id", ""));

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }
}
