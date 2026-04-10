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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler for {@code GET /api/classes/{className}} — returns detailed info about a class.
 *
 * <p>Response includes:
 * <ul>
 *   <li>Class metadata (name, modifiers, superclass, interfaces)</li>
 *   <li>Declared fields (name, type, modifiers)</li>
 *   <li>Declared methods (name, return type, parameter types, modifiers)</li>
 * </ul>
 */
public final class ClassDetailHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(ClassDetailHandler.class.getName());

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String className = pathParams.get("className");
        if (className == null || className.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Class name is required");
        }

        if (!InstrumentationHolder.isAvailable()) {
            return HttpResponseHelper.error(HttpResponseStatus.SERVICE_UNAVAILABLE,
                    "Instrumentation not available");
        }

        // Find the class among loaded classes
        Class<?> targetClass = ClassFinder.findClass(className);
        if (targetClass == null) {
            return HttpResponseHelper.notFound("Class not found: " + className);
        }

        Map<String, Object> detail = buildClassDetail(targetClass);
        return HttpResponseHelper.json(detail);
    }

    private static Map<String, Object> buildClassDetail(Class<?> clazz) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", clazz.getName());
        detail.put("simpleName", clazz.getSimpleName());

        Package pkg = clazz.getPackage();
        detail.put("package", pkg != null ? pkg.getName() : "");

        detail.put("modifiers", Modifier.toString(clazz.getModifiers()));
        detail.put("interface", clazz.isInterface());
        detail.put("annotation", clazz.isAnnotation());
        detail.put("enum", clazz.isEnum());

        // Superclass chain
        Class<?> superclass = clazz.getSuperclass();
        detail.put("superclass", superclass != null ? superclass.getName() : null);

        // Interfaces
        detail.put("interfaces", Arrays.stream(clazz.getInterfaces())
                .map(Class::getName)
                .toList());

        // ClassLoader
        ClassLoader cl = clazz.getClassLoader();
        detail.put("classLoader", cl != null ? cl.toString() : "bootstrap");

        // Fields
        List<Map<String, Object>> fieldList = new ArrayList<>();
        try {
            for (Field field : clazz.getDeclaredFields()) {
                Map<String, Object> fieldInfo = new LinkedHashMap<>();
                fieldInfo.put("name", field.getName());
                fieldInfo.put("type", field.getType().getName());
                fieldInfo.put("modifiers", Modifier.toString(field.getModifiers()));
                fieldList.add(fieldInfo);
            }
        } catch (Exception e) {
            LOG.fine("Cannot read fields for " + clazz.getName() + ": " + e.getMessage());
        }
        detail.put("fields", fieldList);

        // Methods
        List<Map<String, Object>> methodList = new ArrayList<>();
        try {
            for (Method method : clazz.getDeclaredMethods()) {
                Map<String, Object> methodInfo = new LinkedHashMap<>();
                methodInfo.put("name", method.getName());
                methodInfo.put("returnType", method.getReturnType().getName());
                methodInfo.put("parameterTypes", Arrays.stream(method.getParameterTypes())
                        .map(Class::getName)
                        .toList());
                methodInfo.put("modifiers", Modifier.toString(method.getModifiers()));
                methodList.add(methodInfo);
            }
        } catch (Exception e) {
            LOG.fine("Cannot read methods for " + clazz.getName() + ": " + e.getMessage());
        }
        detail.put("methods", methodList);

        return detail;
    }
}
