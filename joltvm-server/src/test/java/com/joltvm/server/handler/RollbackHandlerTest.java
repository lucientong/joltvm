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
import com.joltvm.server.hotswap.BytecodeBackupService;
import com.joltvm.server.hotswap.HotSwapService;
import io.netty.buffer.Unpooled;
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
 * Unit tests for {@link RollbackHandler}.
 */
@DisplayName("RollbackHandler")
class RollbackHandlerTest {

    private RollbackHandler handler;
    private HotSwapService hotSwapService;
    private BytecodeBackupService backupService;

    @BeforeEach
    void setUp() {
        backupService = new BytecodeBackupService();
        hotSwapService = new HotSwapService(backupService);
        handler = new RollbackHandler(hotSwapService);
        InstrumentationHolder.set(new StubInstrumentation());
    }

    @AfterEach
    void tearDown() {
        try {
            var resetMethod = InstrumentationHolder.class.getDeclaredMethod("reset");
            resetMethod.setAccessible(true);
            resetMethod.invoke(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("returns 503 when Instrumentation is not available")
    void returns503WhenInstrumentationUnavailable() {
        tearDown(); // Remove instrumentation

        String json = HttpResponseHelper.gson().toJson(Map.of("className", "com.test.A"));
        FullHttpRequest request = createPostRequest("/api/rollback", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.SERVICE_UNAVAILABLE, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Instrumentation not available"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 for empty body")
    void returns400ForEmptyBody() {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST,
                "/api/rollback");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Request body is required"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 for invalid JSON")
    void returns400ForInvalidJson() {
        FullHttpRequest request = createPostRequest("/api/rollback", "not json");
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("Invalid JSON"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns 400 for missing className")
    void returns400ForMissingClassName() {
        String json = HttpResponseHelper.gson().toJson(Map.of("other", "value"));
        FullHttpRequest request = createPostRequest("/api/rollback", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("className"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("returns failure when no backup exists")
    void returnsFailureWhenNoBackup() {
        String json = HttpResponseHelper.gson().toJson(Map.of("className", "com.test.NoBackup"));
        FullHttpRequest request = createPostRequest("/api/rollback", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":false"));
        assertTrue(body.contains("No backup found"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("rollback succeeds when backup exists for a loaded class")
    void rollbackSucceedsWithBackup() {
        // Add a backup for java.lang.String
        backupService.backup("java.lang.String", new byte[]{1, 2, 3});

        String json = HttpResponseHelper.gson().toJson(Map.of("className", "java.lang.String"));
        FullHttpRequest request = createPostRequest("/api/rollback", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.OK, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":true"));
        assertTrue(body.contains("\"action\":\"ROLLBACK\""));
        assertTrue(body.contains("Successfully rolled back"));

        // Backup should be removed after successful rollback
        assertFalse(backupService.hasBackup("java.lang.String"));

        response.release();
        request.release();
    }

    @Test
    @DisplayName("rollback fails when class not found among loaded classes")
    void rollbackFailsWhenClassNotLoaded() {
        // Add a backup for a class that's not in the stub's loaded classes
        backupService.backup("com.nonexistent.FakeClass", new byte[]{1, 2, 3});

        String json = HttpResponseHelper.gson().toJson(Map.of("className", "com.nonexistent.FakeClass"));
        FullHttpRequest request = createPostRequest("/api/rollback", json);
        FullHttpResponse response = handler.handle(request, Map.of());

        assertEquals(HttpResponseStatus.BAD_REQUEST, response.status());
        String body = response.content().toString(StandardCharsets.UTF_8);
        assertTrue(body.contains("\"success\":false"));
        assertTrue(body.contains("Class not found"));

        response.release();
        request.release();
    }

    private FullHttpRequest createPostRequest(String uri, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri,
                Unpooled.wrappedBuffer(bytes));
    }

    /**
     * Stub Instrumentation.
     */
    private static class StubInstrumentation implements Instrumentation {

        @Override
        public Class[] getAllLoadedClasses() {
            return new Class[]{
                    String.class,
                    Integer.class,
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
        public void redefineClasses(ClassDefinition... definitions) throws UnmodifiableClassException {}

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
