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

package com.joltvm.cli;

import com.joltvm.agent.AttachHelper;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Properties;

/**
 * JoltVM command-line interface entry point.
 *
 * <p>Provides commands for interacting with JoltVM:
 * <ul>
 *   <li>{@code attach <pid>} — Attach the JoltVM agent to a running JVM process</li>
 *   <li>{@code list} — List all running JVM processes</li>
 *   <li>{@code --help} — Show usage information</li>
 *   <li>{@code --version} — Show version information</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java -jar joltvm-cli.jar attach &lt;pid&gt; [agentArgs]
 *   java -jar joltvm-cli.jar list
 *   java -jar joltvm-cli.jar --help
 *   java -jar joltvm-cli.jar --version
 * </pre>
 */
public final class JoltVMCli {

    private static final String VERSION = loadVersion();

    private static final String BANNER = """
            
              ╦╔═╗╦  ╔╦╗╦  ╦╔╦╗
              ║║ ║║   ║ ╚╗╔╝║║║
             ╚╝╚═╝╩═╝ ╩  ╚╝ ╩ ╩
             Like a jolt of electricity
            """;

    private static final String USAGE = """
            Usage: joltvm <command> [options]
            
            Commands:
              attach <pid> [agentArgs]    Attach JoltVM agent to a running JVM process
              list                        List all running JVM processes
            
            Options:
              --help, -h                  Show this help message
              --version, -v               Show version information
            
            Examples:
              joltvm attach 12345
              joltvm attach 12345 port=7758
              joltvm list
            
            Note: Requires a full JDK (not JRE) for the Attach API to work.
            """;

    private JoltVMCli() {
        // Entry point class — no instantiation
    }

    /**
     * CLI entry point.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        if (args.length == 0) {
            printBanner();
            System.out.println(USAGE);
            System.exit(1);
            return;
        }

        String command = args[0];

        switch (command) {
            case "--help", "-h" -> {
                printBanner();
                System.out.println(USAGE);
            }
            case "--version", "-v" -> {
                System.out.println("JoltVM " + VERSION);
            }
            case "attach" -> handleAttach(args);
            case "list" -> handleList();
            default -> {
                System.err.println("Unknown command: " + command);
                System.out.println();
                System.out.println(USAGE);
                System.exit(1);
            }
        }
    }

    /**
     * Handles the 'attach' command.
     *
     * @param args command-line arguments (args[0]="attach", args[1]=pid, args[2]=agentArgs)
     */
    private static void handleAttach(String[] args) {
        if (args.length < 2) {
            System.err.println("Error: Missing PID argument.");
            System.err.println("Usage: joltvm attach <pid> [agentArgs]");
            System.exit(1);
            return;
        }

        String pid = args[1];
        String agentArgs = args.length > 2 ? args[2] : null;

        System.out.println(BANNER);
        System.out.printf("Attaching JoltVM agent to JVM process [pid=%s]...%n", pid);

        try {
            AttachHelper.attach(pid, agentArgs);
            System.out.println();
            System.out.printf("✓ Successfully attached JoltVM agent to JVM [pid=%s]%n", pid);
            System.out.println("  The JoltVM agent is now running inside the target JVM.");
            System.out.println("  Web IDE will be available at http://localhost:7758 once the server module is enabled.");
        } catch (Exception e) {
            System.err.println();
            System.err.printf("✗ Failed to attach to JVM [pid=%s]%n", pid);
            System.err.printf("  Error: %s%n", e.getMessage());
            System.err.println();
            System.err.println("Troubleshooting:");
            System.err.println("  1. Verify the PID is correct: run 'jps -l' to list JVM processes");
            System.err.println("  2. Ensure you're running a full JDK (not JRE)");
            System.err.println("  3. Ensure you have permission to attach to the target process");
            System.err.println("  4. On macOS, you may need to run with sudo");
            System.exit(1);
        }
    }

    /**
     * Handles the 'list' command — lists all running JVM processes.
     */
    private static void handleList() {
        System.out.println("Running JVM processes:");
        System.out.println();

        List<VirtualMachineDescriptor> vms = AttachHelper.listJvmProcesses();

        if (vms.isEmpty()) {
            System.out.println("  (no JVM processes found)");
            return;
        }

        System.out.printf("  %-10s %s%n", "PID", "DISPLAY NAME");
        System.out.printf("  %-10s %s%n", "---", "------------");

        for (VirtualMachineDescriptor vm : vms) {
            String displayName = vm.displayName();
            if (displayName == null || displayName.isBlank()) {
                displayName = "(unknown)";
            }
            System.out.printf("  %-10s %s%n", vm.id(), displayName);
        }

        System.out.println();
        System.out.printf("Total: %d process(es)%n", vms.size());
        System.out.println();
        System.out.println("To attach: joltvm attach <pid>");
    }

    private static void printBanner() {
        System.out.println(BANNER);
    }

    /**
     * Loads the project version from {@code version.properties} on the classpath.
     *
     * <p>The properties file is generated at build time by Gradle's
     * {@code processResources} task, which expands the {@code projectVersion}
     * property from {@code gradle.properties}.
     *
     * @return the version string, or "unknown" if the properties file cannot be read
     */
    private static String loadVersion() {
        try (InputStream in = JoltVMCli.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return props.getProperty("version", "unknown");
            }
        } catch (IOException ignored) {
            // Fall through to default
        }
        return "unknown";
    }
}
