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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiHandlerTest {

    @Test
    void servesBundledOpenApi31Document() {
        FullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/openapi.json");
        FullHttpResponse response = new OpenApiHandler().handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        assertTrue(response.headers().get(HttpHeaderNames.CONTENT_TYPE)
                .startsWith("application/vnd.oai.openapi+json"));
        assertEquals("no-cache", response.headers().get(HttpHeaderNames.CACHE_CONTROL));

        JsonObject document = JsonParser.parseString(
                response.content().toString(StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("3.1.0", document.get("openapi").getAsString());
        assertEquals("1.4.0", document.getAsJsonObject("info").get("version").getAsString());
        assertTrue(document.getAsJsonObject("paths").has("/api/health"));

        response.release();
        request.release();
    }
}
