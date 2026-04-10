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

package com.joltvm.server;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpResponseHelper}.
 */
class HttpResponseHelperTest {

    @Test
    @DisplayName("json response has correct content type and status")
    void jsonResponse() {
        FullHttpResponse response = HttpResponseHelper.json(Map.of("key", "value"));

        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(response.headers().get("Content-Type").contains("application/json"));

        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"key\""));
        assertTrue(body.contains("\"value\""));

        response.release();
    }

    @Test
    @DisplayName("json response with custom status")
    void jsonResponseWithStatus() {
        FullHttpResponse response = HttpResponseHelper.json(
                HttpResponseStatus.CREATED, Map.of("id", 1));

        assertEquals(HttpResponseStatus.CREATED, response.status());
        response.release();
    }

    @Test
    @DisplayName("text response has correct content type")
    void textResponse() {
        FullHttpResponse response = HttpResponseHelper.text("hello");

        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(response.headers().get("Content-Type").contains("text/plain"));

        String body = response.content().toString(StandardCharsets.UTF_8);
        assertEquals("hello", body);

        response.release();
    }

    @Test
    @DisplayName("error response includes status code and message")
    void errorResponse() {
        FullHttpResponse response = HttpResponseHelper.error(
                HttpResponseStatus.BAD_REQUEST, "Invalid input");

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());

        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Invalid input"));
        assertTrue(body.contains("400"));

        response.release();
    }

    @Test
    @DisplayName("notFound response returns 404")
    void notFoundResponse() {
        FullHttpResponse response = HttpResponseHelper.notFound("Resource not found");

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        response.release();
    }

    @Test
    @DisplayName("serverError response returns 500")
    void serverErrorResponse() {
        FullHttpResponse response = HttpResponseHelper.serverError("Something went wrong");

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        response.release();
    }

    @Test
    @DisplayName("CORS headers are present")
    void corsHeaders() {
        FullHttpResponse response = HttpResponseHelper.json(Map.of());

        assertEquals("*", response.headers().get("Access-Control-Allow-Origin"));
        assertNotNull(response.headers().get("Access-Control-Allow-Methods"));

        response.release();
    }
}
