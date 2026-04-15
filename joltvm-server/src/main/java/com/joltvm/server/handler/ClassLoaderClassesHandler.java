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
import com.joltvm.server.classloader.ClassLoaderService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/classloaders/{id}/classes} — lists classes loaded
 * by the specified ClassLoader.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code page} — page number (0-based, default 0)</li>
 *   <li>{@code size} — page size (default 100, max 5000)</li>
 *   <li>{@code search} — case-insensitive class name filter</li>
 * </ul>
 */
public class ClassLoaderClassesHandler implements RouteHandler {

    private final ClassLoaderService classLoaderService;

    public ClassLoaderClassesHandler(ClassLoaderService classLoaderService) {
        this.classLoaderService = classLoaderService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String loaderId = pathParams.get("id");
        if (loaderId == null || loaderId.isEmpty()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Missing ClassLoader id");
        }

        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        int page = getIntParam(decoder, "page", 0);
        int size = getIntParam(decoder, "size", 100);
        String search = getStringParam(decoder, "search");

        if (page < 0) page = 0;
        if (size < 1) size = 100;

        return HttpResponseHelper.json(classLoaderService.getClassesByLoader(loaderId, page, size, search));
    }

    private int getIntParam(QueryStringDecoder decoder, String name, int defaultVal) {
        List<String> values = decoder.parameters().get(name);
        if (values != null && !values.isEmpty()) {
            try {
                return Integer.parseInt(values.get(0));
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultVal;
    }

    private String getStringParam(QueryStringDecoder decoder, String name) {
        List<String> values = decoder.parameters().get(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }
}
