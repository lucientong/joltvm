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

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for dynamically attaching the JoltVM agent to a running JVM process.
 *
 * <p>Uses the JDK Attach API ({@code com.sun.tools.attach.VirtualMachine}) to load
 * the JoltVM agent JAR into a target JVM identified by its process ID (PID).
 *
 * <p><b>Requirements:</b>
 * <ul>
 *   <li>Must be run on a full JDK (not JRE) — the Attach API is part of the {@code jdk.attach} module</li>
 *   <li>The current user must have permission to attach to the target JVM process</li>
 *   <li>The agent JAR must be accessible from the filesystem</li>
 * </ul>
 *
 * @see JoltVMAgent#agentmain(String, java.lang.instrument.Instrumentation)
 */
public final class AttachHelper {

    private static final Logger LOG = Logger.getLogger(AttachHelper.class.getName());

    /** System property that overrides agent JAR resolution. */
    public static final String AGENT_JAR_PROPERTY = "joltvm.agent.jar";

    /** Environment variable that overrides agent JAR resolution. */
    public static final String AGENT_JAR_ENV = "JOLTVM_AGENT_JAR";

    /** Environment variable for the JoltVM home directory (Docker / install layout). */
    public static final String JOLTVM_HOME_ENV = "JOLTVM_HOME";

    /** Classpath resource path of the embedded canonical agent fat JAR (CLI packaging). */
    public static final String EMBEDDED_AGENT_RESOURCE = "/agent/joltvm-agent-all.jar.embedded";

    private AttachHelper() {
        // Utility class — no instantiation
    }

    /**
     * Attaches the JoltVM agent to the target JVM process with no agent arguments.
     *
     * @param pid the process ID of the target JVM
     * @throws Exception if attachment fails
     */
    public static void attach(String pid) throws Exception {
        attach(pid, null);
    }

    /**
     * Attaches the JoltVM agent to the target JVM process.
     *
     * @param pid       the process ID of the target JVM
     * @param agentArgs optional comma-separated key=value arguments for the agent
     * @throws Exception if attachment fails
     */
    public static void attach(String pid, String agentArgs) throws Exception {
        attach(pid, agentArgs, null);
    }

    /**
     * Attaches the JoltVM agent using an explicit agent JAR path when provided.
     *
     * @param pid          the process ID of the target JVM
     * @param agentArgs    optional agent arguments
     * @param agentJarPath explicit path to the canonical agent fat JAR; may be null
     * @throws Exception if attachment fails
     */
    public static void attach(String pid, String agentArgs, String agentJarPath) throws Exception {
        if (pid == null || pid.isBlank()) {
            throw new IllegalArgumentException("PID must not be null or blank");
        }

        try {
            long pidValue = Long.parseLong(pid.trim());
            if (pidValue <= 0) {
                throw new IllegalArgumentException(
                        String.format("PID must be a positive integer, got: %s", pid));
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    String.format("PID must be a numeric value, got: '%s'", pid), e);
        }

        String resolvedJar = agentJarPath != null && !agentJarPath.isBlank()
                ? validateAgentJar(new File(agentJarPath)).getAbsolutePath()
                : resolveAgentJarPath();
        LOG.info(String.format("Attaching JoltVM agent to JVM [pid=%s, agentJar=%s]", pid, resolvedJar));

        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            LOG.info(String.format("Successfully attached to JVM [pid=%s]", pid));

            vm.loadAgent(resolvedJar, agentArgs);
            LOG.info(String.format("JoltVM agent loaded into JVM [pid=%s]", pid));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, String.format("Failed to attach JoltVM agent to JVM [pid=%s]", pid), e);
            throw e;
        } finally {
            if (vm != null) {
                try {
                    vm.detach();
                    LOG.fine(String.format("Detached from JVM [pid=%s]", pid));
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to detach from target JVM", e);
                }
            }
        }
    }

    /**
     * Lists all running JVM processes visible to the current user.
     *
     * @return a list of JVM process descriptors
     */
    public static List<VirtualMachineDescriptor> listJvmProcesses() {
        return VirtualMachine.list();
    }

    /**
     * Resolves the path to the canonical JoltVM agent fat JAR.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code -Djoltvm.agent.jar}</li>
     *   <li>{@code JOLTVM_AGENT_JAR} environment variable</li>
     *   <li>{@code $JOLTVM_HOME/joltvm-agent.jar}</li>
     *   <li>Sibling {@code joltvm-agent.jar} / {@code joltvm-agent-*-all.jar} next to the current JAR</li>
     *   <li>Embedded classpath resource {@code /agent/joltvm-agent-all.jar} (CLI packaging)</li>
     *   <li>CodeSource of {@link JoltVMAgent} only when that JAR declares {@code Agent-Class}</li>
     *   <li>IDE fallback: {@code build/libs/joltvm-agent-*-all.jar}</li>
     * </ol>
     *
     * @return the absolute path to a validated agent JAR
     * @throws IllegalStateException if no valid agent JAR can be found
     */
    public static String resolveAgentJarPath() {
        String propertyPath = System.getProperty(AGENT_JAR_PROPERTY);
        if (propertyPath != null && !propertyPath.isBlank()) {
            return validateAgentJar(new File(propertyPath)).getAbsolutePath();
        }

        String envPath = System.getenv(AGENT_JAR_ENV);
        if (envPath != null && !envPath.isBlank()) {
            return validateAgentJar(new File(envPath)).getAbsolutePath();
        }

        String home = System.getenv(JOLTVM_HOME_ENV);
        if (home != null && !home.isBlank()) {
            File homeJar = new File(home, "joltvm-agent.jar");
            if (homeJar.isFile() && hasAgentClass(homeJar)) {
                return homeJar.getAbsolutePath();
            }
        }

        File codeSourceFile = getCodeSourceFile();
        if (codeSourceFile != null) {
            File sibling = findSiblingAgentJar(codeSourceFile);
            if (sibling != null) {
                return sibling.getAbsolutePath();
            }
        }

        File embedded = extractEmbeddedAgentJar();
        if (embedded != null) {
            return embedded.getAbsolutePath();
        }

        if (codeSourceFile != null && codeSourceFile.getName().endsWith(".jar") && hasAgentClass(codeSourceFile)) {
            return codeSourceFile.getAbsolutePath();
        }

        if (codeSourceFile != null && !codeSourceFile.getName().endsWith(".jar")) {
            File buildLibs = findBuildLibs(codeSourceFile);
            if (buildLibs != null && buildLibs.isDirectory()) {
                File[] shadowJars = buildLibs.listFiles(
                        (dir, name) -> name.startsWith("joltvm-agent") && name.endsWith("-all.jar"));
                if (shadowJars != null && shadowJars.length > 0) {
                    File chosen = shadowJars[0];
                    if (hasAgentClass(chosen)) {
                        LOG.info(String.format("Running from IDE, using shadow JAR: %s",
                                chosen.getAbsolutePath()));
                        return chosen.getAbsolutePath();
                    }
                }
            }
        }

        throw new IllegalStateException(
                "Cannot locate a valid JoltVM agent fat JAR. "
                        + "Set -Djoltvm.agent.jar or JOLTVM_AGENT_JAR, place joltvm-agent.jar "
                        + "next to the CLI, or build with './gradlew :joltvm-distribution:shadowJar'.");
    }

    /**
     * Validates that the given file exists and declares {@code Agent-Class}.
     *
     * @param jar the candidate agent JAR
     * @return the same file if valid
     * @throws IllegalStateException if the file is missing or not an agent JAR
     */
    public static File validateAgentJar(File jar) {
        if (jar == null || !jar.isFile()) {
            throw new IllegalStateException(
                    "JoltVM agent JAR not found: " + (jar != null ? jar.getAbsolutePath() : "null"));
        }
        if (!hasAgentClass(jar)) {
            throw new IllegalStateException(
                    "File is not a valid JoltVM agent JAR (missing Agent-Class): " + jar.getAbsolutePath());
        }
        return jar;
    }

    /**
     * Returns whether the JAR declares {@code Agent-Class} in its manifest.
     *
     * @param jar the JAR file to inspect
     * @return {@code true} if Agent-Class is present
     */
    public static boolean hasAgentClass(File jar) {
        try (JarFile jarFile = new JarFile(jar)) {
            Manifest manifest = jarFile.getManifest();
            if (manifest == null) {
                return false;
            }
            Attributes attrs = manifest.getMainAttributes();
            String agentClass = attrs.getValue("Agent-Class");
            return agentClass != null && !agentClass.isBlank();
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to read manifest from " + jar, e);
            return false;
        }
    }

    private static File getCodeSourceFile() {
        try {
            CodeSource codeSource = JoltVMAgent.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                return null;
            }
            return new File(codeSource.getLocation().toURI());
        } catch (URISyntaxException e) {
            LOG.log(Level.FINE, "Failed to resolve CodeSource URI", e);
            return null;
        }
    }

    private static File findSiblingAgentJar(File codeSourceFile) {
        File dir = codeSourceFile.isDirectory() ? codeSourceFile : codeSourceFile.getParentFile();
        if (dir == null || !dir.isDirectory()) {
            return null;
        }

        File exact = new File(dir, "joltvm-agent.jar");
        if (exact.isFile() && hasAgentClass(exact) && !exact.equals(codeSourceFile)) {
            return exact;
        }

        File[] candidates = dir.listFiles(
                (d, name) -> name.startsWith("joltvm-agent") && name.endsWith("-all.jar"));
        if (candidates != null) {
            for (File candidate : candidates) {
                if (!candidate.equals(codeSourceFile) && hasAgentClass(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private static File findBuildLibs(File codeSourceFile) {
        // Walk up looking for distribution / agent build/libs directories
        File current = codeSourceFile;
        for (int i = 0; i < 12 && current != null; i++) {
            File distLibs = new File(current, "joltvm-distribution/build/libs");
            if (distLibs.isDirectory()) {
                return distLibs;
            }
            File agentLibs = new File(current, "joltvm-agent/build/libs");
            if (agentLibs.isDirectory()) {
                return agentLibs;
            }
            File libs = new File(current, "libs");
            if (libs.isDirectory()) {
                return libs;
            }
            current = current.getParentFile();
        }
        return null;
    }

    /**
     * Extracts the embedded agent fat JAR from the classpath into a versioned cache directory.
     *
     * @return the extracted file, or {@code null} if the resource is not present
     */
    private static File extractEmbeddedAgentJar() {
        try (InputStream in = AttachHelper.class.getResourceAsStream(EMBEDDED_AGENT_RESOURCE)) {
            if (in == null) {
                return null;
            }

            String version = JoltVMAgent.class.getPackage() != null
                    ? JoltVMAgent.class.getPackage().getImplementationVersion()
                    : null;
            if (version == null || version.isBlank()) {
                version = "dev";
            }

            Path cacheDir = Path.of(System.getProperty("java.io.tmpdir"), "joltvm-agent-cache", version);
            Files.createDirectories(cacheDir);
            Path target = cacheDir.resolve("joltvm-agent-all.jar");

            if (Files.isRegularFile(target) && Files.size(target) > 0 && hasAgentClass(target.toFile())) {
                return target.toFile();
            }

            Path temp = Files.createTempFile(cacheDir, "joltvm-agent-", ".jar.tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                in.transferTo(out);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailed) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }

            File extracted = target.toFile();
            if (hasAgentClass(extracted)) {
                LOG.info("Using embedded agent JAR extracted to: " + extracted.getAbsolutePath());
                return extracted;
            }
            Files.deleteIfExists(target);
            return null;
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to extract embedded agent JAR", e);
            return null;
        }
    }
}
