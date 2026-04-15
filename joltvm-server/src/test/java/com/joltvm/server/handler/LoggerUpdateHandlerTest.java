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

import com.joltvm.server.logger.JulAdapter;
import com.joltvm.server.logger.LoggerService;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LoggerUpdateHandlerTest {

    private final LoggerService loggerService = new LoggerService(new JulAdapter());

    @Test
    void returns200OnValidLevelChange() {
        LoggerUpdateHandler handler = new LoggerUpdateHandler(loggerService);
        String body = "{\"level\": \"DEBUG\"}";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/api/loggers/com.test",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of("name", "com.joltvm.test.handler"));

        assertEquals(HttpResponseStatus.OK, response.status());
        String responseBody = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(responseBody.contains("\"newLevel\""));
    }

    @Test
    void returns400WhenMissingName() {
        LoggerUpdateHandler handler = new LoggerUpdateHandler(loggerService);
        String body = "{\"level\": \"DEBUG\"}";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/api/loggers/",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }

    @Test
    void returns400WhenMissingLevelField() {
        LoggerUpdateHandler handler = new LoggerUpdateHandler(loggerService);
        String body = "{}";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/api/loggers/test",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of("name", "com.test"));

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }

    @Test
    void returns400ForInvalidJson() {
        LoggerUpdateHandler handler = new LoggerUpdateHandler(loggerService);
        String body = "not json";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.PUT, "/api/loggers/test",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of("name", "com.test"));

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }
}
