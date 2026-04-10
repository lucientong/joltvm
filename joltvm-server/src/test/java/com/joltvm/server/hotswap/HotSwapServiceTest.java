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

package com.joltvm.server.hotswap;

import com.joltvm.agent.InstrumentationHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HotSwapService}.
 */
@DisplayName("HotSwapService")
class HotSwapServiceTest {

    private HotSwapService service;
    private BytecodeBackupService backupService;

    @BeforeEach
    void setUp() {
        backupService = new BytecodeBackupService();
        service = new HotSwapService(backupService);
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

    // ── History tests (no Instrumentation needed) ──

    @Test
    @DisplayName("history is empty initially")
    void historyIsEmptyInitially() {
        List<HotSwapRecord> history = service.getHistory();
        assertTrue(history.isEmpty());
    }

    @Test
    @DisplayName("getHistory returns unmodifiable list")
    void getHistoryReturnsUnmodifiableList() {
        List<HotSwapRecord> history = service.getHistory();
        assertThrows(UnsupportedOperationException.class, () -> history.add(null));
    }

    @Test
    @DisplayName("getHistory with limit returns at most limit items")
    void getHistoryWithLimit() {
        List<HotSwapRecord> history = service.getHistory(5);
        assertTrue(history.isEmpty());
        assertEquals(0, history.size());
    }

    @Test
    @DisplayName("getRollbackableClasses is empty initially")
    void rollbackableClassesEmptyInitially() {
        Set<String> rollbackable = service.getRollbackableClasses();
        assertTrue(rollbackable.isEmpty());
    }

    @Test
    @DisplayName("getBackupService returns the injected backup service")
    void getBackupServiceReturnsInjected() {
        assertSame(backupService, service.getBackupService());
    }

    // ── Hot-swap failure tests ──

    @Test
    @DisplayName("hotSwap fails when redefine is not supported")
    void hotSwapFailsWhenRedefineNotSupported() {
        InstrumentationHolder.set(new StubInstrumentation() {
            @Override
            public boolean isRedefineClassesSupported() {
                return false;
            }
        });

        HotSwapRecord record = service.hotSwap("com.example.Test", new byte[]{1, 2, 3});

        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("not supported"));
        assertEquals(1, service.getHistory().size());
    }

    @Test
    @DisplayName("hotSwap fails when class is not found")
    void hotSwapFailsWhenClassNotFound() {
        InstrumentationHolder.set(new StubInstrumentation());

        HotSwapRecord record = service.hotSwap("com.nonexistent.FakeClass", new byte[]{1, 2, 3});

        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("Class not found"));
    }

    @Test
    @DisplayName("hotSwap fails when class is not modifiable")
    void hotSwapFailsWhenClassNotModifiable() {
        InstrumentationHolder.set(new StubInstrumentation() {
            @Override
            public boolean isModifiableClass(Class<?> theClass) {
                return false;
            }
        });

        HotSwapRecord record = service.hotSwap("java.lang.String", new byte[]{1, 2, 3});

        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("not modifiable"));
    }

    @Test
    @DisplayName("hotSwap fails when redefineClasses throws ClassFormatError")
    void hotSwapFailsOnClassFormatError() {
        InstrumentationHolder.set(new StubInstrumentation() {
            @Override
            public void redefineClasses(ClassDefinition... definitions) {
                throw new ClassFormatError("bad class format");
            }
        });

        HotSwapRecord record = service.hotSwap("java.lang.String", new byte[]{1, 2, 3});

        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("Invalid class format"));
    }

    @Test
    @DisplayName("hotSwap fails when redefineClasses throws UnsupportedOperationException")
    void hotSwapFailsOnUnsupportedOperation() {
        InstrumentationHolder.set(new StubInstrumentation() {
            @Override
            public void redefineClasses(ClassDefinition... definitions) {
                throw new UnsupportedOperationException("structural change");
            }
        });

        HotSwapRecord record = service.hotSwap("java.lang.String", new byte[]{1, 2, 3});

        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("Structural change"));
    }

    @Test
    @DisplayName("hotSwap fails when redefineClasses throws UnmodifiableClassException")
    void hotSwapFailsOnUnmodifiableClassException() {
        InstrumentationHolder.set(new StubInstrumentation() {
            @Override
            public void redefineClasses(ClassDefinition... definitions) throws UnmodifiableClassException {
                throw new UnmodifiableClassException("cannot modify");
            }
        });

        HotSwapRecord record = service.hotSwap("java.lang.String", new byte[]{1, 2, 3});

        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("cannot be modified"));
    }

    @Test
    @DisplayName("hotSwap succeeds and records history")
    void hotSwapSucceedsAndRecordsHistory() {
        InstrumentationHolder.set(new StubInstrumentation());

        HotSwapRecord record = service.hotSwap("java.lang.String", new byte[]{1, 2, 3});

        assertEquals(HotSwapRecord.Action.HOTSWAP, record.action());
        assertEquals(HotSwapRecord.Status.SUCCESS, record.status());
        assertTrue(record.message().contains("Successfully redefined"));
        assertEquals("java.lang.String", record.className());
        assertNotNull(record.id());
        assertNotNull(record.timestamp());

        // Verify history
        List<HotSwapRecord> history = service.getHistory();
        assertEquals(1, history.size());
        assertEquals(record.id(), history.get(0).id());
    }

    // ── Rollback tests ──

    @Test
    @DisplayName("rollback fails when no backup exists")
    void rollbackFailsWithoutBackup() {
        InstrumentationHolder.set(new StubInstrumentation());

        HotSwapRecord record = service.rollback("com.example.NoBackup");

        assertEquals(HotSwapRecord.Action.ROLLBACK, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("No backup found"));
    }

    @Test
    @DisplayName("rollback fails when class not found after backup exists")
    void rollbackFailsWhenClassNotFound() {
        InstrumentationHolder.set(new StubInstrumentation());

        // Manually add a backup for a non-loaded class
        backupService.backup("com.nonexistent.FakeClass", new byte[]{1, 2, 3});

        HotSwapRecord record = service.rollback("com.nonexistent.FakeClass");

        assertEquals(HotSwapRecord.Action.ROLLBACK, record.action());
        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        assertTrue(record.message().contains("Class not found"));
    }

    @Test
    @DisplayName("rollback succeeds when backup exists and class is loaded")
    void rollbackSucceeds() {
        InstrumentationHolder.set(new StubInstrumentation());

        // Manually add a backup for java.lang.String
        backupService.backup("java.lang.String", new byte[]{10, 20, 30});
        assertTrue(backupService.hasBackup("java.lang.String"));

        HotSwapRecord record = service.rollback("java.lang.String");

        assertEquals(HotSwapRecord.Action.ROLLBACK, record.action());
        assertEquals(HotSwapRecord.Status.SUCCESS, record.status());
        assertTrue(record.message().contains("Successfully rolled back"));

        // Backup should be removed after rollback
        assertFalse(backupService.hasBackup("java.lang.String"));
    }

    @Test
    @DisplayName("rollback failure preserves backup")
    void rollbackFailurePreservesBackup() {
        InstrumentationHolder.set(new StubInstrumentation() {
            @Override
            public void redefineClasses(ClassDefinition... definitions) {
                throw new RuntimeException("simulated failure");
            }
        });

        backupService.backup("java.lang.String", new byte[]{10, 20, 30});

        HotSwapRecord record = service.rollback("java.lang.String");

        assertEquals(HotSwapRecord.Status.FAILED, record.status());
        // Backup should still be there since rollback failed
        assertTrue(backupService.hasBackup("java.lang.String"));
    }

    // ── History ordering and limit tests ──

    @Test
    @DisplayName("history is ordered newest first")
    void historyOrderedNewestFirst() {
        InstrumentationHolder.set(new StubInstrumentation());

        // Perform two operations
        service.hotSwap("java.lang.String", new byte[]{1});
        service.hotSwap("java.lang.Integer", new byte[]{2});

        List<HotSwapRecord> history = service.getHistory();
        assertEquals(2, history.size());
        assertEquals("java.lang.Integer", history.get(0).className());
        assertEquals("java.lang.String", history.get(1).className());
    }

    @Test
    @DisplayName("getHistory with limit truncates results")
    void getHistoryWithLimitTruncates() {
        InstrumentationHolder.set(new StubInstrumentation());

        service.hotSwap("java.lang.String", new byte[]{1});
        service.hotSwap("java.lang.Integer", new byte[]{2});
        service.hotSwap("java.lang.Thread", new byte[]{3});

        List<HotSwapRecord> limited = service.getHistory(2);
        assertEquals(2, limited.size());
        // Newest first
        assertEquals("java.lang.Thread", limited.get(0).className());
        assertEquals("java.lang.Integer", limited.get(1).className());
    }

    @Test
    @DisplayName("getRollbackableClasses reflects backup state")
    void getRollbackableClassesReflectsBackupState() {
        backupService.backup("com.example.A", new byte[]{1});
        backupService.backup("com.example.B", new byte[]{2});

        Set<String> rollbackable = service.getRollbackableClasses();
        assertEquals(2, rollbackable.size());
        assertTrue(rollbackable.contains("com.example.A"));
        assertTrue(rollbackable.contains("com.example.B"));
    }

    /**
     * Stub Instrumentation that returns a controlled set of classes.
     * By default, supports redefine and treats all classes as modifiable.
     */
    private static class StubInstrumentation implements Instrumentation {

        @Override
        public Class[] getAllLoadedClasses() {
            return new Class[]{
                    String.class,
                    Integer.class,
                    Thread.class,
                    java.util.ArrayList.class
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
