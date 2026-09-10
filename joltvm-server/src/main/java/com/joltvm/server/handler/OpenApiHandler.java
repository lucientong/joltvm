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
import com.joltvm.server.RouteHandler;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

/**
 * Serves the bundled OpenAPI 3.1 contract.
 */
public final class OpenApiHandler implements RouteHandler {

    public static final String RESOURCE_PATH = "openapi/joltvm-openapi.json";

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        try (InputStream input = OpenApiHandler.class.getClassLoader()
                .getResourceAsStream(RESOURCE_PATH)) {
            if (input == null) {
                return HttpResponseHelper.serverError("OpenAPI document is unavailable.");
            }

            byte[] content = input.readAllBytes();
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.OK,
                    Unpooled.wrappedBuffer(content));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE,
                    "application/vnd.oai.openapi+json;version=3.1.0");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            return response;
        } catch (IOException e) {
            return HttpResponseHelper.serverError("Failed to read OpenAPI document.");
        }
    }
}
