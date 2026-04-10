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
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.List;
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

    private AttachHelper() {
        // Utility class — no instantiation
    }

    /**
     * Attaches the JoltVM agent to the target JVM process with no agent arguments.
     *
     * @param pid the process ID of the target JVM
     * @throws Exception if attachment fails (e.g., invalid PID, permission denied,
     *                   agent JAR not found, or target JVM does not support attachment)
     */
    public static void attach(String pid) throws Exception {
        attach(pid, null);
    }

    /**
     * Attaches the JoltVM agent to the target JVM process.
     *
     * <p>This method performs the following steps:
     * <ol>
     *   <li>Resolves the path to the JoltVM agent JAR</li>
     *   <li>Attaches to the target JVM via its PID</li>
     *   <li>Loads the agent JAR into the target JVM</li>
     *   <li>Detaches from the target JVM</li>
     * </ol>
     *
     * @param pid       the process ID of the target JVM
     * @param agentArgs optional comma-separated key=value arguments for the agent
     *                  (e.g., "port=7758"); may be null
     * @throws Exception if attachment fails
     */
    public static void attach(String pid, String agentArgs) throws Exception {
        if (pid == null || pid.isBlank()) {
            throw new IllegalArgumentException("PID must not be null or blank");
        }

        // Validate PID format — must be a positive integer
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

        String agentJarPath = getAgentJarPath();
        LOG.info(String.format("Attaching JoltVM agent to JVM [pid=%s, agentJar=%s]", pid, agentJarPath));

        VirtualMachine vm = null;
        try {
            vm = VirtualMachine.attach(pid);
            LOG.info(String.format("Successfully attached to JVM [pid=%s]", pid));

            vm.loadAgent(agentJarPath, agentArgs);
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
     * Resolves the path to the JoltVM agent JAR.
     *
     * <p>The JAR path is determined from the code source location of the
     * {@link JoltVMAgent} class. This works correctly when the agent is
     * packaged as a JAR file (both regular and shadow/fat JARs).
     *
     * @return the absolute path to the agent JAR file
     * @throws IllegalStateException if the agent JAR path cannot be determined
     */
    private static String getAgentJarPath() {
        try {
            CodeSource codeSource = JoltVMAgent.class.getProtectionDomain().getCodeSource();
            if (codeSource == null) {
                throw new IllegalStateException(
                        "Cannot determine JoltVM agent JAR path: CodeSource is null. "
                                + "Ensure the agent classes are loaded from a JAR file.");
            }

            File agentJar = new File(codeSource.getLocation().toURI());
            if (!agentJar.exists()) {
                throw new IllegalStateException(
                        String.format("JoltVM agent JAR not found at: %s", agentJar.getAbsolutePath()));
            }

            if (!agentJar.getName().endsWith(".jar")) {
                // Running from IDE (classes directory) — look for the shadow JAR in build/libs
                File buildLibs = new File(agentJar.getParentFile().getParentFile(), "libs");
                if (buildLibs.isDirectory()) {
                    File[] shadowJars = buildLibs.listFiles(
                            (dir, name) -> name.startsWith("joltvm-agent") && name.endsWith("-all.jar"));
                    if (shadowJars != null && shadowJars.length > 0) {
                        LOG.info(String.format("Running from IDE, using shadow JAR: %s",
                                shadowJars[0].getAbsolutePath()));
                        return shadowJars[0].getAbsolutePath();
                    }
                }

                throw new IllegalStateException(
                        "JoltVM agent is not running from a JAR file. "
                                + "Build the project with './gradlew shadowJar' first, "
                                + "or specify the agent JAR path explicitly.");
            }

            return agentJar.getAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Failed to resolve JoltVM agent JAR path", e);
        }
    }
}
