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
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler for {@code GET /api/spring/beans/{beanName}} — returns detailed info about a Spring bean.
 *
 * <p>Response includes:
 * <ul>
 *   <li>Bean metadata (name, type, scope, stereotypes)</li>
 *   <li>Superclass, interfaces, annotations</li>
 *   <li>Declared methods with annotations</li>
 *   <li>Request mappings (if this bean is a controller)</li>
 * </ul>
 *
 * <p>Response format:
 * <pre>
 * {
 *   "name": "userController",
 *   "type": "com.example.UserController",
 *   "scope": "singleton",
 *   "stereotypes": ["RestController", "Controller"],
 *   "superclass": "java.lang.Object",
 *   "interfaces": ["com.example.BaseController"],
 *   "annotations": ["org.springframework.web.bind.annotation.RestController"],
 *   "methods": [...],
 *   "requestMappings": [...]
 * }
 * </pre>
 */
public final class BeanDetailHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(BeanDetailHandler.class.getName());

    private final SpringContextService springService;

    public BeanDetailHandler(SpringContextService springService) {
        this.springService = springService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        if (!springService.isSpringDetected()) {
            return HttpResponseHelper.error(HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Spring Framework not detected in target JVM");
        }

        String beanName = pathParams.get("beanName");
        if (beanName == null || beanName.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Bean name is required");
        }

        Map<String, Object> detail = springService.getBeanDetail(beanName);
        if (detail == null) {
            return HttpResponseHelper.notFound("Bean not found: " + beanName);
        }

        return HttpResponseHelper.json(detail);
    }
}
