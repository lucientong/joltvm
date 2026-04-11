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
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClassDetailHandler}.
 */
class ClassDetailHandlerTest {

    private final ClassDetailHandler handler = new ClassDetailHandler();

    @BeforeEach
    void setUp() {
        InstrumentationHolder.set(new StubInstrumentation());
    }

    @AfterEach
    void tearDown() {
        InstrumentationHolder.reset();
    }

    @Test
    @DisplayName("returns 400 when className is missing")
    void returnsBadRequestWhenClassNameMissing() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 when className is blank")
    void returnsBadRequestWhenClassNameBlank() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/ ");
        Map<String, String> params = new HashMap<>();
        params.put("className", "  ");
        FullHttpResponse response = handler.handle(request, params);

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 503 when Instrumentation is not available")
    void returnsServiceUnavailableWithoutInstrumentation() {
        tearDown();

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/java.lang.String");
        FullHttpResponse response = handler.handle(request, Map.of("className", "java.lang.String"));

        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 404 for non-existent class")
    void returnsNotFoundForNonExistentClass() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/com.nonexistent.FakeClass");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "com.nonexistent.FakeClass"));

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Class not found"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns class detail for java.lang.String")
    void returnsClassDetailForString() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/java.lang.String");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "java.lang.String"));

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"name\":\"java.lang.String\""));
        assertTrue(body.contains("\"simpleName\":\"String\""));
        assertTrue(body.contains("\"fields\""));
        assertTrue(body.contains("\"methods\""));
        assertTrue(body.contains("\"superclass\":\"java.lang.Object\""));
        assertTrue(body.contains("\"interfaces\""));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns detail for an interface (Map)")
    void returnsDetailForInterface() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/java.util.Map");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "java.util.Map"));

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"interface\":true"));
        assertTrue(body.contains("\"name\":\"java.util.Map\""));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("detail includes modifiers as string")
    void detailIncludesModifiers() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/java.lang.String");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "java.lang.String"));

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        // String is "public final"
        assertTrue(body.contains("\"modifiers\":\"public final\""));

        response.release();
        request.release();
    }

    /**
     * Stub Instrumentation that returns a controlled set of classes.
     */
    private static class StubInstrumentation implements Instrumentation {

        @Override
        public Class[] getAllLoadedClasses() {
            return new Class[]{
                    String.class,
                    Integer.class,
                    Map.class,
                    java.util.ArrayList.class,
                    Thread.class
            };
        }

        @Override
        public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {}

        @Override
        public void addTransformer(ClassFileTransformer transformer) {}

        @Override
        public boolean removeTransformer(ClassFileTransformer transformer) { return false; }

        @Override
        public boolean isRetransformClassesSupported() { return true; }

        @Override
        public void retransformClasses(Class<?>... classes) {}

        @Override
        public boolean isRedefineClassesSupported() { return true; }

        @Override
        public void redefineClasses(ClassDefinition... definitions) {}

        @Override
        public boolean isModifiableClass(Class<?> theClass) { return true; }

        @Override
        public Class[] getInitiatedClasses(ClassLoader loader) { return new Class[0]; }

        @Override
        public long getObjectSize(Object objectToSize) { return 0; }

        @Override
        public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {}

        @Override
        public void appendToSystemClassLoaderSearch(JarFile jarfile) {}

        @Override
        public boolean isNativeMethodPrefixSupported() { return false; }

        @Override
        public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) {}

        @Override
        public boolean isModifiableModule(Module module) { return true; }

        @Override
        public void redefineModule(Module module, java.util.Set<Module> extraReads,
                                   java.util.Map<String, java.util.Set<Module>> extraExports,
                                   java.util.Map<String, java.util.Set<Module>> extraOpens,
                                   java.util.Set<Class<?>> extraUses,
                                   java.util.Map<Class<?>, java.util.List<Class<?>>> extraProvides) {}
    }
}
