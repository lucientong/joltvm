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

import com.joltvm.server.ognl.OgnlService;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OgnlEvalHandlerTest {

    private static OgnlService ognlService;

    @BeforeAll
    static void setUp() {
        ognlService = new OgnlService();
    }

    @AfterAll
    static void tearDown() {
        ognlService.shutdown();
    }

    @Test
    void returns200ForValidExpression() {
        OgnlEvalHandler handler = new OgnlEvalHandler(ognlService);
        String body = "{\"expression\": \"1 + 1\"}";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/ognl/eval",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String responseBody = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(responseBody.contains("\"success\":true"));
    }

    @Test
    void returns200WithSecurityErrorForDangerousExpression() {
        OgnlEvalHandler handler = new OgnlEvalHandler(ognlService);
        String body = "{\"expression\": \"@java.lang.Runtime@getRuntime()\"}";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/ognl/eval",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String responseBody = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(responseBody.contains("\"success\":false"));
        assertTrue(responseBody.contains("SECURITY"));
    }

    @Test
    void returns400ForMissingExpression() {
        OgnlEvalHandler handler = new OgnlEvalHandler(ognlService);
        String body = "{}";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/ognl/eval",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }

    @Test
    void returns400ForInvalidJson() {
        OgnlEvalHandler handler = new OgnlEvalHandler(ognlService);
        String body = "not json";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/ognl/eval",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }

    @Test
    void returns400ForTooLongExpression() {
        OgnlEvalHandler handler = new OgnlEvalHandler(ognlService);
        String longExpr = "a".repeat(5000);
        String body = "{\"expression\": \"" + longExpr + "\"}";
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/ognl/eval",
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
    }
}
