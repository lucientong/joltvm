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
import com.joltvm.server.spring.SpringContextService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/spring/beans} — lists all Spring beans in the target JVM.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code package} — filter by package prefix (e.g., {@code com.example})</li>
 *   <li>{@code search} — search by bean name or class simple name (case-insensitive)</li>
 *   <li>{@code stereotype} — filter by stereotype (Controller, Service, Repository, etc.)</li>
 *   <li>{@code page} — page number, 1-based (default: 1)</li>
 *   <li>{@code size} — page size (default: 100, max: 1000)</li>
 * </ul>
 *
 * <p>Response format:
 * <pre>
 * {
 *   "springDetected": true,
 *   "total": 123,
 *   "page": 1,
 *   "size": 100,
 *   "beans": [
 *     {
 *       "name": "userService",
 *       "type": "com.example.UserService",
 *       "simpleName": "UserService",
 *       "package": "com.example",
 *       "scope": "singleton",
 *       "stereotypes": ["Service"],
 *       "singleton": true
 *     }
 *   ]
 * }
 * </pre>
 */
public final class BeanListHandler implements RouteHandler {


    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;

    private final SpringContextService springService;

    public BeanListHandler(SpringContextService springService) {
        this.springService = springService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!springService.isSpringDetected()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("springDetected", false);
            result.put("message", "Spring Framework not detected in target JVM");
            result.put("total", 0);
            result.put("beans", List.of());
            return HttpResponseHelper.json(result);
        }

        // Parse query parameters
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String packageFilter = getParam(decoder, "package", null);
        String searchFilter = getParam(decoder, "search", null);
        String stereotypeFilter = getParam(decoder, "stereotype", null);
        int page = getIntParam(decoder, "page", 1);
        int size = Math.min(getIntParam(decoder, "size", DEFAULT_PAGE_SIZE), MAX_PAGE_SIZE);

        List<Map<String, Object>> allBeans = springService.listBeans();

        // Apply filters
        List<Map<String, Object>> filtered = allBeans.stream()
                .filter(bean -> {
                    if (packageFilter != null) {
                        String pkg = (String) bean.getOrDefault("package", "");
                        if (!pkg.startsWith(packageFilter)) {
                            return false;
                        }
                    }
                    if (searchFilter != null) {
                        String name = ((String) bean.getOrDefault("name", "")).toLowerCase();
                        String simpleName = ((String) bean.getOrDefault("simpleName", "")).toLowerCase();
                        String keyword = searchFilter.toLowerCase();
                        if (!name.contains(keyword) && !simpleName.contains(keyword)) {
                            return false;
                        }
                    }
                    if (stereotypeFilter != null) {
                        @SuppressWarnings("unchecked")
                        List<String> stereotypes = (List<String>) bean.getOrDefault("stereotypes", List.of());
                        boolean match = stereotypes.stream()
                                .anyMatch(s -> s.equalsIgnoreCase(stereotypeFilter));
                        if (!match) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();

        int total = filtered.size();
        int fromIndex = Math.min((page - 1) * size, total);
        int toIndex = Math.min(fromIndex + size, total);
        List<Map<String, Object>> pageItems = filtered.subList(fromIndex, toIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("springDetected", true);
        result.put("total", total);
        result.put("page", page);
        result.put("size", size);
        result.put("beans", pageItems);

        return HttpResponseHelper.json(result);
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
