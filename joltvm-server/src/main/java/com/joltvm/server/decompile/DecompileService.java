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

import org.benf.cfr.reader.api.CfrDriver;
import org.benf.cfr.reader.api.ClassFileSource;
import org.benf.cfr.reader.api.OutputSinkFactory;
import org.benf.cfr.reader.api.SinkReturns;
import org.benf.cfr.reader.bytecode.analysis.parse.utils.Pair;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for decompiling Java class bytecode to source code using the CFR decompiler.
 *
 * <p>Obtains bytecode from the JVM's ClassLoader (reading the {@code .class} resource)
 * and passes it to CFR for decompilation.
 *
 * <p>Thread-safe: each decompilation creates its own CFR driver instance.
 */
public class DecompileService {

    private static final Logger LOG = Logger.getLogger(DecompileService.class.getName());

    /**
     * Decompiles a loaded class and returns Java source code.
     *
     * @param clazz the class to decompile
     * @return the decompiled Java source code
     * @throws DecompileException if the class bytecode cannot be read or decompiled
     */
    public String decompile(Class<?> clazz) {
        String className = clazz.getName();
        byte[] bytecode = loadBytecode(clazz);

        return decompileFromBytecode(className, bytecode);
    }

    /**
     * Decompiles bytecode directly.
     *
     * @param className the fully qualified class name
     * @param bytecode  the class bytecode
     * @return the decompiled Java source code
     * @throws DecompileException if decompilation fails
     */
    public String decompileFromBytecode(String className, byte[] bytecode) {
        // Create an in-memory ClassFileSource backed by the bytecode
        String internalName = className.replace('.', '/');
        String resourcePath = "/" + internalName + ".class";

        // Capture decompiled output
        StringBuilder output = new StringBuilder();

        OutputSinkFactory sinkFactory = new OutputSinkFactory() {
            @Override
            public List<SinkClass> getSupportedSinks(SinkType sinkType, Collection<SinkClass> available) {
                return List.of(SinkClass.DECOMPILED);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> Sink<T> getSink(SinkType sinkType, SinkClass sinkClass) {
                if (sinkType == SinkType.JAVA && sinkClass == SinkClass.DECOMPILED) {
                    return (Sink<T>) (Sink<SinkReturns.Decompiled>) decompiled -> {
                        output.append(decompiled.getJava());
                    };
                }
                return t -> {};
            }
        };

        ClassFileSource classFileSource = new BytecodeClassFileSource(resourcePath, bytecode);

        Map<String, String> options = new HashMap<>();
        options.put("showversion", "false");
        options.put("comments", "false");
        options.put("innerclasses", "false");

        try {
            CfrDriver driver = new CfrDriver.Builder()
                    .withClassFileSource(classFileSource)
                    .withOutputSink(sinkFactory)
                    .withOptions(options)
                    .build();

            driver.analyse(List.of(resourcePath));
        } catch (Exception e) {
            throw new DecompileException("CFR decompilation failed for " + className, e);
        }

        String result = output.toString();
        if (result.isEmpty()) {
            throw new DecompileException("CFR produced empty output for " + className);
        }

        return result;
    }

    /**
     * Loads the bytecode of a class from its ClassLoader.
     *
     * @param clazz the class to load bytecode for
     * @return the raw bytecode
     * @throws DecompileException if the bytecode cannot be read
     */
    private byte[] loadBytecode(Class<?> clazz) {
        String resourceName = "/" + clazz.getName().replace('.', '/') + ".class";

        try (InputStream is = clazz.getResourceAsStream(resourceName)) {
            if (is == null) {
                // Try without leading slash
                String altName = clazz.getName().replace('.', '/') + ".class";
                try (InputStream is2 = clazz.getClassLoader() != null
                        ? clazz.getClassLoader().getResourceAsStream(altName) : null) {
                    if (is2 == null) {
                        throw new DecompileException(
                                "Cannot read bytecode for " + clazz.getName()
                                        + ". The class may be generated dynamically or loaded by a restricted ClassLoader.");
                    }
                    return is2.readAllBytes();
                }
            }
            return is.readAllBytes();
        } catch (IOException e) {
            throw new DecompileException("Failed to read bytecode for " + clazz.getName(), e);
        }
    }

    /**
     * In-memory ClassFileSource that serves a single class bytecode to CFR.
     */
    private static final class BytecodeClassFileSource implements ClassFileSource {

        private final String path;
        private final byte[] bytecode;

        BytecodeClassFileSource(String path, byte[] bytecode) {
            this.path = path;
            this.bytecode = bytecode;
        }

        @Override
        public void informAnalysisRelativePathDetail(String usePath, String classFilePath) {
            // No-op
        }

        @Override
        public Collection<String> addJar(String jarPath) {
            return Collections.emptyList();
        }

        @Override
        public String getPossiblyRenamedPath(String path) {
            return path;
        }

        @Override
        public Pair<byte[], String> getClassFileContent(String inputPath) throws IOException {
            if (inputPath.equals(path)) {
                return new Pair<>(bytecode, inputPath);
            }
            // For inner classes or references, try to load from the current classloader
            String className = inputPath;
            if (className.startsWith("/")) {
                className = className.substring(1);
            }
            if (className.endsWith(".class")) {
                className = className.substring(0, className.length() - 6);
            }

            try (InputStream is = ClassLoader.getSystemResourceAsStream(className + ".class")) {
                if (is != null) {
                    return new Pair<>(is.readAllBytes(), inputPath);
                }
            }

            throw new IOException("Cannot find class file: " + inputPath);
        }
    }
}
