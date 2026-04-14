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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for {@code GET /api/spring/dependencies} — returns the full dependency
 * graph showing {@code @Controller → @Service → @Repository} relationships.
 *
 * <p>Lists all beans that have stereotypes and their injected dependencies,
 * providing a global view of the application's component wiring.
 *
 * <p>Response format:
 * <pre>
 * {
 *   "springDetected": true,
 *   "total": 5,
 *   "graph": [
 *     {
 *       "beanName": "userController",
 *       "className": "com.example.UserController",
 *       "stereotypes": ["RestController"],
 *       "dependencies": [
 *         { "fieldName": "userService", "fieldType": "...", "beanName": "userService", "stereotypes": ["Service"] }
 *       ]
 *     }
 *   ]
 * }
 * </pre>
 *
 * @see com.joltvm.server.spring.SpringContextService#getDependencyGraph()
 */
public final class DependencyGraphHandler implements RouteHandler {


    private final SpringContextService springService;

    public DependencyGraphHandler(SpringContextService springService) {
        this.springService = springService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!springService.isSpringDetected()) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("springDetected", false);
            result.put("message", "Spring Framework not detected in target JVM");
            result.put("total", 0);
            result.put("graph", List.of());
            return HttpResponseHelper.json(result);
        }

        List<Map<String, Object>> graph = springService.getDependencyGraph();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("springDetected", true);
        result.put("total", graph.size());
        result.put("graph", graph);

        return HttpResponseHelper.json(result);
    }
}
