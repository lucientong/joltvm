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

package com.joltvm.server.spring;

import com.joltvm.agent.InstrumentationHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link SpringContextService}.
 *
 * <p>Since JoltVM has no compile-time Spring dependency, these tests verify
 * the service's behavior in a non-Spring JVM (Spring not detected), plus
 * utility methods and state management.
 */
@DisplayName("SpringContextService")
class SpringContextServiceTest {

    private SpringContextService service;

    @BeforeEach
    void setUp() {
        service = new SpringContextService();
    }

    @AfterEach
    void tearDown() {
        service.reset();
        // Ensure InstrumentationHolder is clean
        if (InstrumentationHolder.isAvailable()) {
            InstrumentationHolder.reset();
        }
    }

    @Nested
    @DisplayName("Without Instrumentation")
    class WithoutInstrumentation {

        @Test
        @DisplayName("isSpringDetected returns false when Instrumentation not available")
        void springNotDetectedWithoutInstrumentation() {
            assertFalse(service.isSpringDetected());
        }

        @Test
        @DisplayName("listBeans returns empty list when Instrumentation not available")
        void listBeansEmptyWithoutInstrumentation() {
            List<Map<String, Object>> beans = service.listBeans();
            assertTrue(beans.isEmpty());
        }

        @Test
        @DisplayName("getRequestMappings returns empty list when Instrumentation not available")
        void getMappingsEmptyWithoutInstrumentation() {
            List<Map<String, Object>> mappings = service.getRequestMappings();
            assertTrue(mappings.isEmpty());
        }

        @Test
        @DisplayName("getBeanDetail returns null when Instrumentation not available")
        void getBeanDetailNullWithoutInstrumentation() {
            assertNull(service.getBeanDetail("anyBean"));
        }

        @Test
        @DisplayName("getContexts returns empty list when Instrumentation not available")
        void getContextsEmptyWithoutInstrumentation() {
            List<Object> contexts = service.getContexts();
            assertTrue(contexts.isEmpty());
        }
    }

    @Nested
    @DisplayName("With Stub Instrumentation (no Spring classes)")
    class WithStubInstrumentation {

        @BeforeEach
        void setUpInstrumentation() {
            InstrumentationHolder.set(new StubInstrumentation());
        }

        @AfterEach
        void tearDownInstrumentation() {
            InstrumentationHolder.reset();
        }

        @Test
        @DisplayName("isSpringDetected returns false when no Spring classes loaded")
        void springNotDetectedWithoutSpringClasses() {
            assertFalse(service.isSpringDetected());
        }

        @Test
        @DisplayName("listBeans returns empty list when Spring not detected")
        void listBeansEmptyWithoutSpring() {
            List<Map<String, Object>> beans = service.listBeans();
            assertTrue(beans.isEmpty());
        }

        @Test
        @DisplayName("getRequestMappings returns empty list when Spring not detected")
        void getMappingsEmptyWithoutSpring() {
            List<Map<String, Object>> mappings = service.getRequestMappings();
            assertTrue(mappings.isEmpty());
        }

        @Test
        @DisplayName("getBeanDetail returns null when Spring not detected")
        void getBeanDetailNullWithoutSpring() {
            assertNull(service.getBeanDetail("anyBean"));
        }

        @Test
        @DisplayName("refresh can be called multiple times safely")
        void refreshIdempotent() {
            service.refresh();
            assertFalse(service.isSpringDetected());

            service.refresh();
            assertFalse(service.isSpringDetected());
        }

        @Test
        @DisplayName("getContexts returns unmodifiable list")
        void getContextsUnmodifiable() {
            List<Object> contexts = service.getContexts();
            assertThrows(UnsupportedOperationException.class, () -> contexts.add(new Object()));
        }
    }

    @Nested
    @DisplayName("State management")
    class StateManagement {

        @Test
        @DisplayName("reset clears all cached state")
        void resetClearsState() {
            // Trigger initial scan (without instrumentation)
            service.isSpringDetected();

            service.reset();

            // After reset, calling isSpringDetected triggers a new scan
            // (will still be false without Spring, but verifies no exceptions)
            assertFalse(service.isSpringDetected());
        }

        @Test
        @DisplayName("lazy scan: isSpringDetected triggers scan on first call")
        void lazyScanning() {
            // Before any call, internal state should be "not scanned"
            // After calling isSpringDetected, it should be scanned
            boolean result = service.isSpringDetected();
            assertFalse(result);

            // Calling again should not re-scan (cached result)
            // Both calls return same result
            assertEquals(result, service.isSpringDetected());
        }
    }

    @Nested
    @DisplayName("normalizePath utility")
    class NormalizePath {

        @Test
        @DisplayName("combines class and method paths")
        void combinesPaths() {
            assertEquals("/api/users", SpringContextService.normalizePath("/api", "/users"));
        }

        @Test
        @DisplayName("handles empty class path")
        void emptyClassPath() {
            assertEquals("/users", SpringContextService.normalizePath("", "/users"));
        }

        @Test
        @DisplayName("handles empty method path")
        void emptyMethodPath() {
            assertEquals("/api", SpringContextService.normalizePath("/api", ""));
        }

        @Test
        @DisplayName("handles both empty paths")
        void bothEmptyPaths() {
            assertEquals("/", SpringContextService.normalizePath("", ""));
        }

        @Test
        @DisplayName("collapses multiple slashes")
        void collapsesSlashes() {
            assertEquals("/api/users", SpringContextService.normalizePath("/api/", "/users"));
        }

        @Test
        @DisplayName("removes trailing slash")
        void removesTrailingSlash() {
            assertEquals("/api/users", SpringContextService.normalizePath("/api", "users/"));
        }

        @Test
        @DisplayName("ensures leading slash")
        void ensuresLeadingSlash() {
            assertEquals("/api/users", SpringContextService.normalizePath("api", "users"));
        }

        @Test
        @DisplayName("handles path variables")
        void handlesPathVariables() {
            assertEquals("/api/users/{id}", SpringContextService.normalizePath("/api", "/users/{id}"));
        }

        @Test
        @DisplayName("root path preserved")
        void rootPath() {
            assertEquals("/", SpringContextService.normalizePath("/", ""));
        }
    }

    /**
     * Stub Instrumentation that returns a small set of non-Spring classes.
     */
    @SuppressWarnings("rawtypes")
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
