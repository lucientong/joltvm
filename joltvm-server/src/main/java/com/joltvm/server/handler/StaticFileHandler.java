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
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for serving static Web UI files from the classpath.
 *
 * <p>Static files are loaded from the {@code webui/} directory on the classpath.
 * The handler supports common web content types (HTML, CSS, JavaScript, images,
 * fonts, JSON, SVG) and sets appropriate {@code Content-Type} headers.
 *
 * <p>When the requested file is not found, a 404 response is returned.
 * When the path is {@code "/"} or empty, {@code index.html} is served.
 *
 * @see HttpResponseHelper
 */
public final class StaticFileHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(StaticFileHandler.class.getName());

    /** Base directory on the classpath where Web UI files are stored. */
    static final String WEBUI_BASE = "webui/";

    /** Default file served for root path requests. */
    static final String INDEX_FILE = "index.html";

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String resourcePath = pathParams.getOrDefault("filePath", "");

        // Serve index.html for empty path
        if (resourcePath.isEmpty()) {
            resourcePath = INDEX_FILE;
        }

        // Security: prevent path traversal and null-byte injection
        if (resourcePath.contains("..") || resourcePath.contains("\\")
                || resourcePath.indexOf('\0') >= 0) {
            return HttpResponseHelper.error(HttpResponseStatus.FORBIDDEN,
                    "Path traversal is not allowed");
        }

        String fullPath = WEBUI_BASE + resourcePath;

        try (InputStream is = getClassLoader().getResourceAsStream(fullPath)) {
            if (is == null) {
                return HttpResponseHelper.notFound("Static file not found: " + resourcePath);
            }

            byte[] content = is.readAllBytes();
            String contentType = resolveContentType(resourcePath);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(content));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, content.length);

            // Cache control: immutable assets (with hash) cache for 1 year,
            // HTML files always revalidate
            if (resourcePath.endsWith(".html") || resourcePath.equals(INDEX_FILE)) {
                response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            } else {
                response.headers().set(HttpHeaderNames.CACHE_CONTROL, "public, max-age=86400");
            }

            return response;

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error reading static file: " + fullPath, e);
            return HttpResponseHelper.serverError("Error reading static file: " + e.getMessage());
        }
    }

    /**
     * Resolves the MIME content type based on file extension.
     *
     * <p>Uses a {@code switch} expression on the extracted extension for O(1) lookup
     * instead of linear if-else chain scanning, improving both readability and performance.
     *
     * @param path the file path
     * @return the content type string
     */
    static String resolveContentType(String path) {
        Objects.requireNonNull(path, "path");
        String ext = extractExtension(path);

        return switch (ext) {
            case "html", "htm"  -> "text/html; charset=UTF-8";
            case "css"          -> "text/css; charset=UTF-8";
            case "js", "mjs"    -> "application/javascript; charset=UTF-8";
            case "json"         -> "application/json; charset=UTF-8";
            case "svg"          -> "image/svg+xml";
            case "png"          -> "image/png";
            case "jpg", "jpeg"  -> "image/jpeg";
            case "gif"          -> "image/gif";
            case "ico"          -> "image/x-icon";
            case "webp"         -> "image/webp";
            case "woff"         -> "font/woff";
            case "woff2"        -> "font/woff2";
            case "ttf"          -> "font/ttf";
            case "eot"          -> "application/vnd.ms-fontobject";
            case "map"          -> "application/json";
            case "txt"          -> "text/plain; charset=UTF-8";
            case "xml"          -> "application/xml; charset=UTF-8";
            case "wasm"         -> "application/wasm";
            default             -> "application/octet-stream";
        };
    }

    /**
     * Extracts the file extension (lowercase, without the dot).
     *
     * @param path the file path
     * @return the lowercase extension, or empty string if none
     */
    private static String extractExtension(String path) {
        int dotIndex = path.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == path.length() - 1) {
            return "";
        }
        return path.substring(dotIndex + 1).toLowerCase();
    }

    /**
     * Returns the ClassLoader to use for loading resources.
     * Visible for testing.
     *
     * @return the class loader
     */
    ClassLoader getClassLoader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        return cl != null ? cl : StaticFileHandler.class.getClassLoader();
    }
}
