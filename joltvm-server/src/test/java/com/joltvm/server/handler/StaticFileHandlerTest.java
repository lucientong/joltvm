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

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link StaticFileHandler}.
 */
@DisplayName("StaticFileHandler")
class StaticFileHandlerTest {

    private StaticFileHandler handler;
    private FullHttpRequest dummyRequest;

    @BeforeEach
    void setUp() {
        handler = new StaticFileHandler();
        dummyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    }

    // ----------------------------------------------------------------
    // Content Type Resolution
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Content type resolution")
    class ContentTypeResolution {

        @Test
        @DisplayName("resolves HTML content type")
        void html() {
            assertEquals("text/html; charset=UTF-8", StaticFileHandler.resolveContentType("index.html"));
            assertEquals("text/html; charset=UTF-8", StaticFileHandler.resolveContentType("page.htm"));
        }

        @Test
        @DisplayName("resolves CSS content type")
        void css() {
            assertEquals("text/css; charset=UTF-8", StaticFileHandler.resolveContentType("app.css"));
        }

        @Test
        @DisplayName("resolves JavaScript content type")
        void javascript() {
            assertEquals("application/javascript; charset=UTF-8", StaticFileHandler.resolveContentType("app.js"));
            assertEquals("application/javascript; charset=UTF-8", StaticFileHandler.resolveContentType("module.mjs"));
        }

        @Test
        @DisplayName("resolves JSON content type")
        void json() {
            assertEquals("application/json; charset=UTF-8", StaticFileHandler.resolveContentType("data.json"));
        }

        @Test
        @DisplayName("resolves image content types")
        void images() {
            assertEquals("image/png", StaticFileHandler.resolveContentType("logo.png"));
            assertEquals("image/jpeg", StaticFileHandler.resolveContentType("photo.jpg"));
            assertEquals("image/jpeg", StaticFileHandler.resolveContentType("photo.jpeg"));
            assertEquals("image/gif", StaticFileHandler.resolveContentType("animation.gif"));
            assertEquals("image/svg+xml", StaticFileHandler.resolveContentType("icon.svg"));
            assertEquals("image/x-icon", StaticFileHandler.resolveContentType("favicon.ico"));
            assertEquals("image/webp", StaticFileHandler.resolveContentType("image.webp"));
        }

        @Test
        @DisplayName("resolves font content types")
        void fonts() {
            assertEquals("font/woff", StaticFileHandler.resolveContentType("font.woff"));
            assertEquals("font/woff2", StaticFileHandler.resolveContentType("font.woff2"));
            assertEquals("font/ttf", StaticFileHandler.resolveContentType("font.ttf"));
        }

        @Test
        @DisplayName("resolves other content types")
        void other() {
            assertEquals("text/plain; charset=UTF-8", StaticFileHandler.resolveContentType("readme.txt"));
            assertEquals("application/xml; charset=UTF-8", StaticFileHandler.resolveContentType("config.xml"));
            assertEquals("application/wasm", StaticFileHandler.resolveContentType("module.wasm"));
        }

        @Test
        @DisplayName("returns octet-stream for unknown extensions")
        void unknown() {
            assertEquals("application/octet-stream", StaticFileHandler.resolveContentType("file.xyz"));
            assertEquals("application/octet-stream", StaticFileHandler.resolveContentType("data.bin"));
        }

        @Test
        @DisplayName("is case insensitive")
        void caseInsensitive() {
            assertEquals("text/html; charset=UTF-8", StaticFileHandler.resolveContentType("INDEX.HTML"));
            assertEquals("application/javascript; charset=UTF-8", StaticFileHandler.resolveContentType("APP.JS"));
        }
    }

    // ----------------------------------------------------------------
    // Static File Serving
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("File serving")
    class FileServing {

        @Test
        @DisplayName("serves index.html for empty path")
        void servesIndex() {
            FullHttpResponse response = handler.handle(dummyRequest, Collections.emptyMap());
            // index.html exists in webui/ resources
            assertEquals(HttpResponseStatus.OK, response.status());
            String content = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(content.contains("JoltVM"), "Should contain JoltVM in index.html");
            assertTrue(response.headers().get("Content-Type").startsWith("text/html"));
        }

        @Test
        @DisplayName("serves CSS file")
        void servesCss() {
            FullHttpResponse response = handler.handle(dummyRequest, Map.of("filePath", "css/app.css"));
            assertEquals(HttpResponseStatus.OK, response.status());
            String content = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(content.contains("--bg-primary"), "Should contain CSS variables");
            assertEquals("text/css; charset=UTF-8", response.headers().get("Content-Type"));
        }

        @Test
        @DisplayName("serves JavaScript file")
        void servesJs() {
            FullHttpResponse response = handler.handle(dummyRequest, Map.of("filePath", "js/api.js"));
            assertEquals(HttpResponseStatus.OK, response.status());
            String content = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(content.contains("JoltAPI"), "Should contain JoltAPI");
            assertTrue(response.headers().get("Content-Type").startsWith("application/javascript"));
        }

        @Test
        @DisplayName("returns 404 for non-existent file")
        void notFound() {
            FullHttpResponse response = handler.handle(dummyRequest, Map.of("filePath", "nonexistent.txt"));
            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        }

        @Test
        @DisplayName("blocks path traversal attempts")
        void blocksPathTraversal() {
            FullHttpResponse response = handler.handle(dummyRequest, Map.of("filePath", "../../../etc/passwd"));
            assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        }

        @Test
        @DisplayName("blocks backslash path traversal")
        void blocksBackslashTraversal() {
            FullHttpResponse response = handler.handle(dummyRequest, Map.of("filePath", "..\\..\\etc\\passwd"));
            assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        }

        @Test
        @DisplayName("blocks null byte injection")
        void blocksNullByteInjection() {
            FullHttpResponse response = handler.handle(dummyRequest, Map.of("filePath", "index.html\0.png"));
            assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
        }
    }

    // ----------------------------------------------------------------
    // Content Type Edge Cases
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Content type edge cases")
    class ContentTypeEdgeCases {

        @Test
        @DisplayName("returns octet-stream for path without extension")
        void noExtension() {
            assertEquals("application/octet-stream", StaticFileHandler.resolveContentType("Makefile"));
        }

        @Test
        @DisplayName("returns octet-stream for path ending with dot")
        void endsWithDot() {
            assertEquals("application/octet-stream", StaticFileHandler.resolveContentType("file."));
        }

        @Test
        @DisplayName("resolves eot font content type")
        void eotFont() {
            assertEquals("application/vnd.ms-fontobject", StaticFileHandler.resolveContentType("font.eot"));
        }

        @Test
        @DisplayName("resolves source map content type")
        void sourceMap() {
            assertEquals("application/json", StaticFileHandler.resolveContentType("app.js.map"));
        }

        @Test
        @DisplayName("throws NullPointerException for null path")
        void nullPath() {
            assertThrows(NullPointerException.class, () -> StaticFileHandler.resolveContentType(null));
        }
    }

    // ----------------------------------------------------------------
    // Cache Headers
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Cache headers")
    class CacheHeaders {

        @Test
        @DisplayName("HTML files get no-cache header")
        void htmlNoCache() {
            FullHttpResponse response = handler.handle(dummyRequest, Collections.emptyMap());
            assertEquals("no-cache", response.headers().get("Cache-Control"));
        }

        @Test
        @DisplayName("static assets get max-age header")
        void assetsMaxAge() {
            FullHttpResponse response = handler.handle(dummyRequest, Map.of("filePath", "css/app.css"));
            assertEquals("public, max-age=86400", response.headers().get("Cache-Control"));
        }
    }

    // ----------------------------------------------------------------
    // Content-Length
    // ----------------------------------------------------------------
    @Test
    @DisplayName("sets Content-Length header")
    void setsContentLength() {
        FullHttpResponse response = handler.handle(dummyRequest, Collections.emptyMap());
        int length = response.headers().getInt("Content-Length", -1);
        assertTrue(length > 0, "Content-Length should be positive");
        assertEquals(response.content().readableBytes(), length);
    }
}
