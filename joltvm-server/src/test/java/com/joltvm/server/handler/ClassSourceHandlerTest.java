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
import com.joltvm.server.decompile.DecompileService;
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
 * Unit tests for {@link ClassSourceHandler}.
 */
class ClassSourceHandlerTest {

    private ClassSourceHandler handler;

    @BeforeEach
    void setUp() {
        InstrumentationHolder.set(new StubInstrumentation());
        handler = new ClassSourceHandler();
    }

    @AfterEach
    void tearDown() {
        InstrumentationHolder.reset();
    }

    @Test
    @DisplayName("returns 400 when className is missing")
    void returnsBadRequestWhenClassNameMissing() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes//source");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 when className is blank")
    void returnsBadRequestWhenClassNameBlank() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/ /source");
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
                "/api/classes/java.lang.String/source");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "java.lang.String"));

        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 404 for non-existent class")
    void returnsNotFoundForNonExistentClass() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/com.nonexistent.FakeClass/source");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "com.nonexistent.FakeClass"));

        assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Class not found"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("decompiles java.lang.String and returns source")
    void decompilesStringClass() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/java.lang.String/source");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "java.lang.String"));

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"className\":\"java.lang.String\""));
        assertTrue(body.contains("\"decompiler\":\"CFR\""));
        assertTrue(body.contains("\"source\""));
        // CFR output should contain class declaration
        assertTrue(body.contains("class String"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("decompiles an interface (Comparable)")
    void decompilesInterface() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/java.lang.Comparable/source");
        FullHttpResponse response = handler.handle(request,
                Map.of("className", "java.lang.Comparable"));

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"source\""));
        assertTrue(body.contains("interface Comparable"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 500 when decompilation fails")
    void returns500WhenDecompilationFails() {
        // Use a handler with a failing decompile service
        DecompileService failingService = new DecompileService() {
            @Override
            public String decompile(Class<?> clazz) {
                throw new RuntimeException("Decompilation failed intentionally");
            }
        };

        ClassSourceHandler failingHandler = new ClassSourceHandler(failingService);

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes/java.lang.String/source");
        FullHttpResponse response = failingHandler.handle(request,
                Map.of("className", "java.lang.String"));

        assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Failed to decompile class"));

        response.release();
        request.release();
    }

    /**
     * Stub Instrumentation that returns a controlled set of classes.
     */
    @SuppressWarnings("rawtypes")
    private static class StubInstrumentation implements Instrumentation {

        @Override
        public Class[] getAllLoadedClasses() {
            return new Class[]{
                    String.class,
                    Integer.class,
                    Map.class,
                    Comparable.class,
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
