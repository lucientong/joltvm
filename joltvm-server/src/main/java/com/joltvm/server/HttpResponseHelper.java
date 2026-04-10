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

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Utility class for building HTTP responses.
 *
 * <p>Provides convenience methods for JSON, plain text, and error responses
 * with proper Content-Type headers and CORS support.
 */
public final class HttpResponseHelper {

    private static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .create();

    private HttpResponseHelper() {
        // Utility class
    }

    /**
     * Returns the shared Gson instance.
     *
     * @return Gson instance
     */
    public static Gson gson() {
        return GSON;
    }

    /**
     * Creates a JSON response with HTTP 200 OK.
     *
     * @param body the object to serialize as JSON
     * @return the HTTP response
     */
    public static FullHttpResponse json(Object body) {
        return json(HttpResponseStatus.OK, body);
    }

    /**
     * Creates a JSON response with the specified status.
     *
     * @param status the HTTP status
     * @param body   the object to serialize as JSON
     * @return the HTTP response
     */
    public static FullHttpResponse json(HttpResponseStatus status, Object body) {
        String json = GSON.toJson(body);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        addCorsHeaders(response);
        return response;
    }

    /**
     * Creates a plain text response with HTTP 200 OK.
     *
     * @param text the response body
     * @return the HTTP response
     */
    public static FullHttpResponse text(String text) {
        return text(HttpResponseStatus.OK, text);
    }

    /**
     * Creates a plain text response with the specified status.
     *
     * @param status the HTTP status
     * @param text   the response body
     * @return the HTTP response
     */
    public static FullHttpResponse text(HttpResponseStatus status, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(bytes));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8");
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        addCorsHeaders(response);
        return response;
    }

    /**
     * Creates a JSON error response.
     *
     * @param status  the HTTP status
     * @param message the error message
     * @return the HTTP response
     */
    public static FullHttpResponse error(HttpResponseStatus status, String message) {
        return json(status, Map.of(
                "error", status.reasonPhrase(),
                "message", message,
                "status", status.code()
        ));
    }

    /**
     * Creates a 404 Not Found response.
     *
     * @param message the error message
     * @return the HTTP response
     */
    public static FullHttpResponse notFound(String message) {
        return error(HttpResponseStatus.NOT_FOUND, message);
    }

    /**
     * Creates a 500 Internal Server Error response.
     *
     * @param message the error message
     * @return the HTTP response
     */
    public static FullHttpResponse serverError(String message) {
        return error(HttpResponseStatus.INTERNAL_SERVER_ERROR, message);
    }

    /**
     * Adds CORS headers to allow browser access from any origin.
     *
     * @param response the HTTP response to modify
     */
    private static void addCorsHeaders(FullHttpResponse response) {
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");
    }
}
