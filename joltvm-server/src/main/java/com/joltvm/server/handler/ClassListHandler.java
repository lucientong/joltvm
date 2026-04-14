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

import com.joltvm.agent.InstrumentationHolder;
import com.joltvm.server.HttpResponseHelper;
import com.joltvm.server.RouteHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/classes} — lists all loaded classes in the target JVM.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code package} — filter by package prefix (e.g., {@code com.example})</li>
 *   <li>{@code search} — search by class simple name (case-insensitive substring match)</li>
 *   <li>{@code page} — page number, 1-based (default: 1)</li>
 *   <li>{@code size} — page size (default: 100, max: 1000)</li>
 * </ul>
 *
 * <p>Response format:
 * <pre>
 * {
 *   "total": 12345,
 *   "page": 1,
 *   "size": 100,
 *   "classes": [
 *     {
 *       "name": "com.example.MyClass",
 *       "simpleName": "MyClass",
 *       "package": "com.example",
 *       "classLoader": "app",
 *       "interface": false,
 *       "annotation": false,
 *       "enum": false
 *     }
 *   ]
 * }
 * </pre>
 */
public final class ClassListHandler implements RouteHandler {


    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!InstrumentationHolder.isAvailable()) {
            return HttpResponseHelper.error(HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Instrumentation not available. Agent may not be loaded.");
        }

        Instrumentation inst = InstrumentationHolder.get();
        Class<?>[] allClasses = inst.getAllLoadedClasses();

        // Parse query parameters
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String packageFilter = getParam(decoder, "package", null);
        String searchFilter = getParam(decoder, "search", null);
        int page = getIntParam(decoder, "page", 1);
        int size = Math.min(getIntParam(decoder, "size", DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);

        // Filter classes
        List<Class<?>> filtered = Arrays.stream(allClasses)
                .filter(clazz -> {
                    if (packageFilter != null) {
                        String pkg = clazz.getPackageName();
                        if (!pkg.startsWith(packageFilter)) {
                            return false;
                        }
                    }
                    if (searchFilter != null) {
                        String simpleName = clazz.getSimpleName().toLowerCase();
                        if (!simpleName.contains(searchFilter.toLowerCase())) {
                            return false;
                        }
                    }
                    return true;
                })
                .sorted(Comparator.comparing(Class::getName))
                .toList();

        int total = filtered.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Class<?>> pageItems = filtered.subList(fromIndex, toIndex);

        // Build response
        List<Map<String, Object>> classDtos = new ArrayList<>();
        for (Class<?> clazz : pageItems) {
            classDtos.add(toClassInfo(clazz));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("classes", classDtos);

        return HttpResponseHelper.json(result);
    }

    private static Map<String, Object> toClassInfo(Class<?> clazz) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", clazz.getName());
        info.put("simpleName", clazz.getSimpleName());

        Package pkg = clazz.getPackage();
        info.put("package", pkg != null ? pkg.getName() : "");

        ClassLoader cl = clazz.getClassLoader();
        info.put("classLoader", cl != null ? cl.getClass().getSimpleName() : "bootstrap");

        info.put("interface", clazz.isInterface());
        info.put("annotation", clazz.isAnnotation());
        info.put("enum", clazz.isEnum());
        info.put("array", clazz.isArray());
        info.put("synthetic", clazz.isSynthetic());

        int modifiers = clazz.getModifiers();
        info.put("modifiers", modifiers);

        return info;
    }

    private static String getParam(QueryStringDecoder decoder, String key, String defaultValue) {
        List<String> values = decoder.parameters().get(key);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return defaultValue;
    }

    private static int getIntParam(QueryStringDecoder decoder, String key, int defaultValue) {
        String value = getParam(decoder, key, null);
        if (value != null) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return defaultValue;
            }
        }
        return defaultValue;
    }
}
