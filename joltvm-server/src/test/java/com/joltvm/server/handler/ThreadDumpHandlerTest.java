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

class ThreadDumpHandlerTest {

    private ThreadDumpHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ThreadDumpHandler(new ThreadDiagnosticsService());
    }

    @Test
    void returnsPlainTextContentType() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/threads/dump");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(response.headers().get("Content-Type").contains("text/plain"));
    }

    @Test
    void dumpContainsThreadState() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/threads/dump");
        FullHttpResponse response = handler.handle(request, Map.of());

        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Full thread dump"));
        assertTrue(body.contains("java.lang.Thread.State:"));
    }

    @Test
    void dumpHasContentDisposition() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/threads/dump");
        FullHttpResponse response = handler.handle(request, Map.of());

        String disposition = response.headers().get("Content-Disposition");
        assertNotNull(disposition);
        assertTrue(disposition.contains("thread-dump.txt"));
    }
}
