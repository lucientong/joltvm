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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link BytecodeBackupService}.
 */
@DisplayName("BytecodeBackupService")
class BytecodeBackupServiceTest {

    private BytecodeBackupService service;

    @BeforeEach
    void setUp() {
        service = new BytecodeBackupService();
    }

    @Test
    @DisplayName("backup stores bytecode by class name")
    void backupStoresBytecodeByClassName() {
        byte[] bytecode = {0x01, 0x02, 0x03};
        boolean created = service.backup("com.test.MyClass", bytecode);

        assertTrue(created);
        assertTrue(service.hasBackup("com.test.MyClass"));
        assertEquals(1, service.size());
    }

    @Test
    @DisplayName("getBackup returns stored bytecode")
    void getBackupReturnsStoredBytecode() {
        byte[] bytecode = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        service.backup("com.test.MyClass", bytecode);

        Optional<byte[]> backup = service.getBackup("com.test.MyClass");

        assertTrue(backup.isPresent());
        assertArrayEquals(bytecode, backup.get());
    }

    @Test
    @DisplayName("getBackup returns empty for non-existent class")
    void getBackupReturnsEmptyForNonExistent() {
        Optional<byte[]> backup = service.getBackup("com.test.NonExistent");
        assertTrue(backup.isEmpty());
    }

    @Test
    @DisplayName("backup does not overwrite existing backup")
    void backupDoesNotOverwriteExisting() {
        byte[] original = {0x01, 0x02};
        byte[] updated = {0x03, 0x04};

        assertTrue(service.backup("com.test.MyClass", original));
        assertFalse(service.backup("com.test.MyClass", updated));

        // Should still return original
        Optional<byte[]> backup = service.getBackup("com.test.MyClass");
        assertTrue(backup.isPresent());
        assertArrayEquals(original, backup.get());
    }

    @Test
    @DisplayName("removeBackup removes the backup")
    void removeBackupRemoves() {
        service.backup("com.test.MyClass", new byte[]{0x01});

        assertTrue(service.removeBackup("com.test.MyClass"));
        assertFalse(service.hasBackup("com.test.MyClass"));
        assertEquals(0, service.size());
    }

    @Test
    @DisplayName("removeBackup returns false for non-existent")
    void removeBackupReturnsFalseForNonExistent() {
        assertFalse(service.removeBackup("com.test.NonExistent"));
    }

    @Test
    @DisplayName("getBackedUpClasses returns all class names")
    void getBackedUpClassesReturnsAll() {
        service.backup("com.test.A", new byte[]{0x01});
        service.backup("com.test.B", new byte[]{0x02});
        service.backup("com.test.C", new byte[]{0x03});

        assertEquals(3, service.getBackedUpClasses().size());
        assertTrue(service.getBackedUpClasses().contains("com.test.A"));
        assertTrue(service.getBackedUpClasses().contains("com.test.B"));
        assertTrue(service.getBackedUpClasses().contains("com.test.C"));
    }

    @Test
    @DisplayName("clear removes all backups")
    void clearRemovesAll() {
        service.backup("com.test.A", new byte[]{0x01});
        service.backup("com.test.B", new byte[]{0x02});

        service.clear();

        assertEquals(0, service.size());
        assertFalse(service.hasBackup("com.test.A"));
        assertFalse(service.hasBackup("com.test.B"));
    }

    @Test
    @DisplayName("getBackup returns defensive copy")
    void getBackupReturnsDefensiveCopy() {
        byte[] original = {0x01, 0x02, 0x03};
        service.backup("com.test.MyClass", original);

        byte[] retrieved = service.getBackup("com.test.MyClass").orElseThrow();
        retrieved[0] = (byte) 0xFF; // Modify the copy

        // Original should be unchanged
        byte[] retrievedAgain = service.getBackup("com.test.MyClass").orElseThrow();
        assertEquals((byte) 0x01, retrievedAgain[0]);
    }

    @Test
    @DisplayName("backup with Class object works for real class")
    void backupWithClassObjectWorks() {
        // Use a real JDK class
        boolean created = service.backup(String.class);

        assertTrue(created);
        assertTrue(service.hasBackup("java.lang.String"));

        Optional<byte[]> backup = service.getBackup("java.lang.String");
        assertTrue(backup.isPresent());
        assertTrue(backup.get().length > 0);
    }
}
