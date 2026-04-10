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

package com.joltvm.agent;

import com.sun.tools.attach.VirtualMachineDescriptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AttachHelper}.
 *
 * <p>Note: Tests that require actual JVM attachment (e.g., attaching to a live process)
 * are integration tests and are not included here. This class focuses on parameter
 * validation and non-destructive operations.
 */
class AttachHelperTest {

    // ========================================================================
    // attach() — parameter validation
    // ========================================================================

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("attach with null or empty PID throws IllegalArgumentException")
    void attach_withNullOrEmptyPid_throwsIllegalArgumentException(String pid) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AttachHelper.attach(pid)
        );
        assertTrue(exception.getMessage().contains("PID"));
    }

    @Test
    @DisplayName("attach with blank PID throws IllegalArgumentException")
    void attach_withBlankPid_throwsIllegalArgumentException() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AttachHelper.attach("   ")
        );
        assertTrue(exception.getMessage().contains("PID"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "12.34", "pid123", "1a2b", "-1", "0", "-999"})
    @DisplayName("attach with non-numeric or non-positive PID throws IllegalArgumentException")
    void attach_withInvalidPidFormat_throwsIllegalArgumentException(String pid) {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> AttachHelper.attach(pid)
        );
        assertTrue(exception.getMessage().contains("PID") || exception.getMessage().contains("pid"));
    }

    @Test
    @DisplayName("attach(pid, agentArgs) with null PID throws IllegalArgumentException")
    void attachWithArgs_withNullPid_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AttachHelper.attach(null, "port=7758")
        );
    }

    @Test
    @DisplayName("attach(pid, agentArgs) with blank PID throws IllegalArgumentException")
    void attachWithArgs_withBlankPid_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AttachHelper.attach("  ", "port=7758")
        );
    }

    // ========================================================================
    // listJvmProcesses()
    // ========================================================================

    @Test
    @DisplayName("listJvmProcesses returns non-null list")
    void listJvmProcesses_returnsNonNullList() {
        List<VirtualMachineDescriptor> processes = AttachHelper.listJvmProcesses();
        assertNotNull(processes, "Process list should not be null");
    }

    @Test
    @DisplayName("listJvmProcesses includes at least one process (the current JVM)")
    void listJvmProcesses_includesCurrentJvm() {
        List<VirtualMachineDescriptor> processes = AttachHelper.listJvmProcesses();
        assertFalse(processes.isEmpty(),
                "Process list should include at least the current JVM process");
    }

    @Test
    @DisplayName("listJvmProcesses descriptors have non-null IDs")
    void listJvmProcesses_descriptorsHaveIds() {
        List<VirtualMachineDescriptor> processes = AttachHelper.listJvmProcesses();
        for (VirtualMachineDescriptor descriptor : processes) {
            assertNotNull(descriptor.id(), "Process descriptor ID should not be null");
            assertFalse(descriptor.id().isBlank(), "Process descriptor ID should not be blank");
        }
    }
}
