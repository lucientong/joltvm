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

package com.joltvm.server.classloader;

import com.joltvm.agent.InstrumentationHolder;
import com.joltvm.server.compile.CompileResult;
import com.joltvm.server.compile.InMemoryCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.List;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LoadedClassResolver}, including dual ClassLoader same-name conflict.
 */
@DisplayName("LoadedClassResolver")
class LoadedClassResolverTest {

    private DefiningLoader loaderA;
    private DefiningLoader loaderB;
    private Class<?> classA;
    private Class<?> classB;

    @BeforeEach
    void setUp() {
        InMemoryCompiler compiler = new InMemoryCompiler();
        CompileResult result = compiler.compile("com.demo.Shared",
                "package com.demo;\npublic class Shared { public int v = 1; }\n");
        assertTrue(result.success());
        byte[] bytecode = result.bytecodeMap().get("com.demo.Shared");
        assertNotNull(bytecode);

        loaderA = new DefiningLoader("loader-A");
        loaderB = new DefiningLoader("loader-B");
        classA = loaderA.define("com.demo.Shared", bytecode);
        classB = loaderB.define("com.demo.Shared", bytecode);
        assertNotSame(classA, classB);

        InstrumentationHolder.set(new StubInstrumentation(List.of(classA, classB, String.class)));
    }

    @AfterEach
    void tearDown() {
        InstrumentationHolder.reset();
    }

    @Test
    @DisplayName("unique match resolves without classLoaderId")
    void uniqueMatchResolves() {
        Class<?> resolved = LoadedClassResolver.resolve("java.lang.String", null);
        assertEquals(String.class, resolved);
    }

    @Test
    @DisplayName("dual ClassLoader same name throws AmbiguousClassException without id")
    void dualLoaderAmbiguousWithoutId() {
        AmbiguousClassException ex = assertThrows(AmbiguousClassException.class,
                () -> LoadedClassResolver.resolve("com.demo.Shared", null));
        assertEquals("com.demo.Shared", ex.getClassName());
        assertEquals(2, ex.getCandidates().size());
        assertTrue(ex.getCandidates().stream().allMatch(c -> c.containsKey("classLoaderId")));
    }

    @Test
    @DisplayName("dual ClassLoader resolves with classLoaderId")
    void dualLoaderResolvesWithId() {
        String idA = ClassLoaderService.getLoaderId(loaderA);
        String idB = ClassLoaderService.getLoaderId(loaderB);

        assertSame(classA, LoadedClassResolver.resolve("com.demo.Shared", idA));
        assertSame(classB, LoadedClassResolver.resolve("com.demo.Shared", idB));
    }

    @Test
    @DisplayName("wrong classLoaderId returns null")
    void wrongLoaderIdReturnsNull() {
        assertNull(LoadedClassResolver.resolve("com.demo.Shared", "99999999"));
    }

    @Test
    @DisplayName("unknown class returns null")
    void unknownClassReturnsNull() {
        assertNull(LoadedClassResolver.resolve("com.demo.DoesNotExist", null));
    }

    /** ClassLoader that only defines the bytes we give it (no parent delegation for that name). */
    private static final class DefiningLoader extends ClassLoader {
        private final String label;

        DefiningLoader(String label) {
            super(null);
            this.label = label;
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }

        @Override
        public String toString() {
            return "DefiningLoader(" + label + ")";
        }
    }

    @SuppressWarnings("rawtypes")
    private static class StubInstrumentation implements Instrumentation {
        private final Class[] classes;

        StubInstrumentation(List<Class<?>> classes) {
            this.classes = classes.toArray(Class[]::new);
        }

        @Override
        public Class[] getAllLoadedClasses() {
            return classes;
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
