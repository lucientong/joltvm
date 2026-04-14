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
 * Handler for {@code GET /api/spring/mappings} — lists all URL → method mappings.
 *
 * <p>Parses {@code @RequestMapping}, {@code @GetMapping}, {@code @PostMapping}, etc.
 * from all controller beans and returns a unified mapping table.
 *
 * <p>Query parameters:
 * <ul>
 *   <li>{@code search} — filter by URL pattern or method name (case-insensitive)</li>
 *   <li>{@code method} — filter by HTTP method (GET, POST, etc.)</li>
 * </ul>
 *
 * <p>Response format:
 * <pre>
 * {
 *   "springDetected": true,
 *   "total": 42,
 *   "mappings": [
 *     {
 *       "url": "/api/users/{id}",
 *       "httpMethod": "GET",
 *       "beanName": "userController",
 *       "className": "com.example.UserController",
 *       "method": "getUserById",
 *       "returnType": "com.example.User",
 *       "parameterTypes": ["java.lang.Long"]
 *     }
 *   ]
 * }
 * </pre>
 */
public final class RequestMappingHandler implements RouteHandler {


    private final SpringContextService springService;

    public RequestMappingHandler(SpringContextService springService) {
        this.springService = springService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!springService.isSpringDetected()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("springDetected", false);
            result.put("message", "Spring Framework not detected in target JVM");
            result.put("total", 0);
            result.put("mappings", List.of());
            return HttpResponseHelper.json(result);
        }

        // Parse query parameters
        QueryStringDecoder decoder = new QueryStringDecoder(request.uri());
        String searchFilter = getParam(decoder, "search", null);
        String methodFilter = getParam(decoder, "method", null);

        List<Map<String, Object>> allMappings = springService.getRequestMappings();

        // Apply filters
        List<Map<String, Object>> filtered = allMappings.stream()
                .filter(mapping -> {
                    if (searchFilter != null) {
                        String url = ((String) mapping.getOrDefault("url", "")).toLowerCase();
                        String methodName = ((String) mapping.getOrDefault("method", "")).toLowerCase();
                        String className = ((String) mapping.getOrDefault("className", "")).toLowerCase();
                        String keyword = searchFilter.toLowerCase();
                        if (!url.contains(keyword) && !methodName.contains(keyword)
                                && !className.contains(keyword)) {
                            return false;
                        }
                    }
                    if (methodFilter != null) {
                        String httpMethod = (String) mapping.getOrDefault("httpMethod", "");
                        if (!httpMethod.equalsIgnoreCase(methodFilter)) {
                            return false;
                        }
                    }
                    return true;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("springDetected", true);
        result.put("total", filtered.size());
        result.put("mappings", filtered);

        return HttpResponseHelper.json(result);
    }

    private static String getParam(QueryStringDecoder decoder, String key, String defaultValue) {
        List<String> values = decoder.parameters().get(key);
        if (values != null && !values.isEmpty()) {
            return values.get(0);
        }
        return defaultValue;
    }
}
