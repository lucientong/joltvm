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

package com.joltvm.server.compile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link InMemoryCompiler}.
 */
@DisplayName("InMemoryCompiler")
class InMemoryCompilerTest {

    private InMemoryCompiler compiler;

    @BeforeEach
    void setUp() {
        compiler = new InMemoryCompiler();
    }

    @Test
    @DisplayName("compiles a simple class successfully")
    void compilesSimpleClassSuccessfully() {
        String sourceCode = """
                package com.test;
                
                public class HelloWorld {
                    public String greet() {
                        return "Hello, World!";
                    }
                }
                """;

        CompileResult result = compiler.compile("com.test.HelloWorld", sourceCode);

        assertTrue(result.success());
        assertFalse(result.bytecodeMap().isEmpty());
        assertTrue(result.bytecodeMap().containsKey("com.test.HelloWorld"));
        assertTrue(result.diagnostics().isEmpty());
        assertTrue(result.bytecodeMap().get("com.test.HelloWorld").length > 0);
    }

    @Test
    @DisplayName("compiles a class with no package")
    void compilesClassWithNoPackage() {
        String sourceCode = """
                public class Simple {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        CompileResult result = compiler.compile("Simple", sourceCode);

        assertTrue(result.success());
        assertTrue(result.bytecodeMap().containsKey("Simple"));
    }

    @Test
    @DisplayName("returns failure for syntax errors")
    void returnsFailureForSyntaxErrors() {
        String sourceCode = """
                package com.test;
                
                public class Broken {
                    public void bad() {
                        int x = // missing expression
                    }
                }
                """;

        CompileResult result = compiler.compile("com.test.Broken", sourceCode);

        assertFalse(result.success());
        assertTrue(result.bytecodeMap().isEmpty());
        assertFalse(result.diagnostics().isEmpty());
        // Should contain line number info
        assertTrue(result.diagnostics().get(0).contains("Line"));
    }

    @Test
    @DisplayName("returns failure for type errors")
    void returnsFailureForTypeErrors() {
        String sourceCode = """
                package com.test;
                
                public class TypeError {
                    public String getValue() {
                        return 42;
                    }
                }
                """;

        CompileResult result = compiler.compile("com.test.TypeError", sourceCode);

        assertFalse(result.success());
        assertFalse(result.diagnostics().isEmpty());
    }

    @Test
    @DisplayName("compiles class with methods and fields")
    void compilesClassWithMethodsAndFields() {
        String sourceCode = """
                package com.test;
                
                public class Calculator {
                    private int lastResult;
                    
                    public int add(int a, int b) {
                        lastResult = a + b;
                        return lastResult;
                    }
                    
                    public int getLastResult() {
                        return lastResult;
                    }
                }
                """;

        CompileResult result = compiler.compile("com.test.Calculator", sourceCode);

        assertTrue(result.success());
        assertTrue(result.bytecodeMap().containsKey("com.test.Calculator"));
    }

    @Test
    @DisplayName("compiles class using JDK classes")
    void compilesClassUsingJdkClasses() {
        String sourceCode = """
                package com.test;
                
                import java.util.List;
                import java.util.ArrayList;
                
                public class ListUser {
                    public List<String> getNames() {
                        List<String> names = new ArrayList<>();
                        names.add("Alice");
                        names.add("Bob");
                        return names;
                    }
                }
                """;

        CompileResult result = compiler.compile("com.test.ListUser", sourceCode);

        assertTrue(result.success());
        assertTrue(result.bytecodeMap().containsKey("com.test.ListUser"));
    }

    @Test
    @DisplayName("compiled bytecode is valid class file")
    void compiledBytecodeIsValidClassFile() {
        String sourceCode = """
                package com.test;
                
                public class Valid {
                    public void doSomething() {}
                }
                """;

        CompileResult result = compiler.compile("com.test.Valid", sourceCode);
        assertTrue(result.success());

        byte[] bytecode = result.bytecodeMap().get("com.test.Valid");
        assertNotNull(bytecode);
        assertTrue(bytecode.length > 4);
        // Check magic number (0xCAFEBABE)
        assertEquals((byte) 0xCA, bytecode[0]);
        assertEquals((byte) 0xFE, bytecode[1]);
        assertEquals((byte) 0xBA, bytecode[2]);
        assertEquals((byte) 0xBE, bytecode[3]);
    }

    @Test
    @DisplayName("compile result factory methods work correctly")
    void compileResultFactoryMethods() {
        CompileResult success = CompileResult.success(java.util.Map.of("Test", new byte[]{1, 2, 3}));
        assertTrue(success.success());
        assertEquals(1, success.bytecodeMap().size());
        assertTrue(success.diagnostics().isEmpty());

        CompileResult failure = CompileResult.failure(java.util.List.of("error1", "error2"));
        assertFalse(failure.success());
        assertTrue(failure.bytecodeMap().isEmpty());
        assertEquals(2, failure.diagnostics().size());
    }
}
