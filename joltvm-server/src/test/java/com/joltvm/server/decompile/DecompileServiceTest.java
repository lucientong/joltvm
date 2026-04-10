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

package com.joltvm.server.decompile;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DecompileService}.
 */
class DecompileServiceTest {

    private final DecompileService service = new DecompileService();

    @Test
    @DisplayName("decompile a known JDK class produces valid Java source")
    void decompileJdkClass() {
        // java.util.ArrayList is always loaded — decompile it
        String source = service.decompile(java.util.ArrayList.class);

        assertNotNull(source);
        assertFalse(source.isBlank());
        // The decompiled output should contain the class declaration
        assertTrue(source.contains("ArrayList"), "Should contain class name");
    }

    @Test
    @DisplayName("decompile returns source containing package declaration")
    void decompileContainsPackage() {
        String source = service.decompile(java.util.HashMap.class);

        assertNotNull(source);
        assertTrue(source.contains("java.util"), "Should contain package");
    }

    @Test
    @DisplayName("decompile simple class produces readable output")
    void decompileSimpleClass() {
        // Use String which is always available
        String source = service.decompile(String.class);

        assertNotNull(source);
        assertFalse(source.isBlank());
        assertTrue(source.contains("String"), "Should contain class name String");
    }

    @Test
    @DisplayName("decompile an interface works")
    void decompileInterface() {
        String source = service.decompile(java.util.List.class);

        assertNotNull(source);
        assertTrue(source.contains("List"), "Should contain interface name");
    }

    @Test
    @DisplayName("decompile enum class works")
    void decompileEnum() {
        String source = service.decompile(Thread.State.class);

        assertNotNull(source);
        assertTrue(source.contains("State"), "Should contain enum name");
    }

    @Test
    @DisplayName("decompileFromBytecode with valid bytecode works")
    void decompileFromBytecodeWorks() throws Exception {
        // Read bytecode of this test class itself
        String resourceName = DecompileServiceTest.class.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (var is = DecompileServiceTest.class.getClassLoader().getResourceAsStream(resourceName)) {
            assertNotNull(is, "Should be able to read own bytecode");
            bytecode = is.readAllBytes();
        }

        String source = service.decompileFromBytecode(DecompileServiceTest.class.getName(), bytecode);
        assertNotNull(source);
        assertTrue(source.contains("DecompileServiceTest"));
    }
}
