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

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In-memory Java source code compiler using {@link javax.tools.JavaCompiler}.
 *
 * <p>Compiles Java source code entirely in memory — no temporary files are created.
 * The compiled bytecode is returned as a {@code Map<String, byte[]>} mapping
 * fully qualified class names to their bytecode.
 *
 * <p>Thread-safe: each compilation creates its own compiler task.
 *
 * <h3>Usage:</h3>
 * <pre>
 *   InMemoryCompiler compiler = new InMemoryCompiler();
 *   CompileResult result = compiler.compile("com.example.MyClass", sourceCode);
 *   if (result.success()) {
 *       byte[] bytecode = result.bytecodeMap().get("com.example.MyClass");
 *   }
 * </pre>
 */
public class InMemoryCompiler {

    private static final Logger LOG = Logger.getLogger(InMemoryCompiler.class.getName());

    /**
     * Compiles Java source code in memory.
     *
     * @param className  the fully qualified class name (e.g., {@code com.example.MyClass})
     * @param sourceCode the Java source code
     * @return the compilation result containing bytecode or error diagnostics
     * @throws CompileException if the Java compiler is not available
     */
    public CompileResult compile(String className, String sourceCode) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new CompileException(
                    "Java compiler not available. Ensure a full JDK (not JRE) is being used.");
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();

        // Create in-memory source file
        InMemorySourceFile sourceFile = new InMemorySourceFile(className, sourceCode);

        // Create in-memory file manager to capture compiled bytecode
        Map<String, byte[]> bytecodeMap = new HashMap<>();
        try (JavaFileManager standardFileManager = compiler.getStandardFileManager(diagnostics, null, null);
             InMemoryFileManager fileManager = new InMemoryFileManager(standardFileManager, bytecodeMap)) {

            // Set compilation options: use the current JVM's classpath
            List<String> options = List.of(
                    "-classpath", System.getProperty("java.class.path", "")
            );

            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,           // writer (null = System.err)
                    fileManager,    // file manager
                    diagnostics,    // diagnostic listener
                    options,        // compiler options
                    null,           // annotation classes
                    List.of(sourceFile)  // compilation units
            );

            boolean success = task.call();

            if (success) {
                LOG.fine("Compilation successful for " + className
                        + " (" + bytecodeMap.size() + " class(es) generated)");
                return CompileResult.success(Map.copyOf(bytecodeMap));
            } else {
                List<String> errors = formatDiagnostics(diagnostics);
                LOG.log(Level.WARNING, "Compilation failed for " + className
                        + ": " + errors.size() + " error(s)");
                return CompileResult.failure(errors);
            }
        } catch (CompileException e) {
            throw e;
        } catch (Exception e) {
            throw new CompileException("Compilation failed for " + className, e);
        }
    }

    private List<String> formatDiagnostics(DiagnosticCollector<JavaFileObject> diagnostics) {
        List<String> messages = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                String message = String.format("Line %d: %s", d.getLineNumber(), d.getMessage(null));
                messages.add(message);
            }
        }
        return messages;
    }

    // ========================================================================
    // In-memory JavaFileObject implementations
    // ========================================================================

    /**
     * In-memory Java source file.
     */
    private static final class InMemorySourceFile extends SimpleJavaFileObject {
        private final String sourceCode;

        InMemorySourceFile(String className, String sourceCode) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension),
                    Kind.SOURCE);
            this.sourceCode = sourceCode;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return sourceCode;
        }
    }

    /**
     * In-memory compiled class file that captures bytecode to a byte array.
     */
    private static final class InMemoryClassFile extends SimpleJavaFileObject {
        private final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        private final String className;
        private final Map<String, byte[]> bytecodeMap;

        InMemoryClassFile(String className, Map<String, byte[]> bytecodeMap) {
            super(URI.create("bytes:///" + className.replace('.', '/') + Kind.CLASS.extension),
                    Kind.CLASS);
            this.className = className;
            this.bytecodeMap = bytecodeMap;
        }

        @Override
        public OutputStream openOutputStream() {
            return new ByteArrayOutputStream() {
                @Override
                public void close() throws IOException {
                    super.close();
                    bytecodeMap.put(className, toByteArray());
                }
            };
        }
    }

    /**
     * Custom file manager that intercepts class output and stores it in memory.
     */
    private static final class InMemoryFileManager extends ForwardingJavaFileManager<JavaFileManager> {
        private final Map<String, byte[]> bytecodeMap;

        InMemoryFileManager(JavaFileManager fileManager, Map<String, byte[]> bytecodeMap) {
            super(fileManager);
            this.bytecodeMap = bytecodeMap;
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className,
                                                    JavaFileObject.Kind kind, FileObject sibling) {
            return new InMemoryClassFile(className, bytecodeMap);
        }
    }
}
