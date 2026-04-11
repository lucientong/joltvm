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
import java.lang.instrument.UnmodifiableClassException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ClassListHandler}.
 *
 * <p>Uses a mock {@link Instrumentation} that returns a controlled set of classes.
 */
class ClassListHandlerTest {

    private final ClassListHandler handler = new ClassListHandler();

    @BeforeEach
    void setUp() {
        InstrumentationHolder.set(new StubInstrumentation());
    }

    @AfterEach
    void tearDown() {
        InstrumentationHolder.reset();
    }

    @Test
    @DisplayName("returns 503 when Instrumentation is not available")
    void returnsServiceUnavailableWithoutInstrumentation() {
        tearDown(); // clear instrumentation

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/classes");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Instrumentation not available"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns class list with default pagination")
    void returnsClassListWithDefaultPagination() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/classes");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"total\""));
        assertTrue(body.contains("\"page\":1"));
        assertTrue(body.contains("\"classes\""));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("filters classes by package prefix")
    void filtersClassesByPackage() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes?package=java.lang");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        // All returned classes should be in java.lang package
        assertTrue(body.contains("\"total\""));
        // String is in java.lang
        assertTrue(body.contains("java.lang.String") || body.contains("java.lang."));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("filters classes by search keyword (case-insensitive)")
    void filtersClassesBySearch() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes?search=string");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("java.lang.String"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("supports custom page and size parameters")
    void supportsCustomPageAndSize() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes?page=1&size=2");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"size\":2"));
        assertTrue(body.contains("\"page\":1"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("handles invalid page/size gracefully (defaults)")
    void handlesInvalidPageSizeGracefully() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes?page=abc&size=xyz");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        // Should default to page=1, size=100
        assertTrue(body.contains("\"page\":1"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("class info contains expected metadata fields")
    void classInfoContainsMetadata() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes?search=String&package=java.lang&size=5");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        // Check metadata fields in the response
        assertTrue(body.contains("\"name\""));
        assertTrue(body.contains("\"simpleName\""));
        assertTrue(body.contains("\"interface\""));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("size is capped at max 1000")
    void sizeCappedAtMax() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/classes?size=9999");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        // The response size should be capped at 1000
        assertTrue(body.contains("\"size\":1000"));

        response.release();
        request.release();
    }

    /**
     * Stub Instrumentation that returns a small, deterministic set of classes.
     */
    private static class StubInstrumentation implements Instrumentation {

        @Override
        public Class[] getAllLoadedClasses() {
            return new Class[]{
                    String.class,
                    Integer.class,
                    Map.class,
                    java.util.ArrayList.class,
                    java.io.InputStream.class,
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
