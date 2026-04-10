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

import java.lang.instrument.Instrumentation;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JoltVM Agent entry point.
 *
 * <p>Supports two attachment modes:
 * <ul>
 *   <li><b>premain</b>: Loaded at JVM startup via {@code -javaagent:joltvm-agent.jar}</li>
 *   <li><b>agentmain</b>: Dynamically attached to a running JVM via the Attach API</li>
 * </ul>
 *
 * <p>Both entry points delegate to {@link #initialize(String, Instrumentation)} which stores
 * the {@link Instrumentation} instance in {@link InstrumentationHolder} for use by other
 * JoltVM modules.
 *
 * @see InstrumentationHolder
 * @see AttachHelper
 */
public final class JoltVMAgent {

    private static final Logger LOG = Logger.getLogger(JoltVMAgent.class.getName());

    private static final String BANNER = """
            
              ╦╔═╗╦  ╔╦╗╦  ╦╔╦╗
              ║║ ║║   ║ ╚╗╔╝║║║
             ╚╝╚═╝╩═╝ ╩  ╚╝ ╩ ╩
             Like a jolt of electricity
            """;

    private static volatile boolean initialized = false;

    private JoltVMAgent() {
        // Utility class — no instantiation
    }

    /**
     * Resets the agent to its uninitialized state.
     *
     * <p><b>WARNING:</b> This method is intended for testing purposes only.
     * Do not call in production code.
     */
    static void reset() {
        initialized = false;
        InstrumentationHolder.reset();
    }

    /**
     * Entry point for startup attachment.
     *
     * <p>Invoked by the JVM when the agent is specified via the {@code -javaagent} flag
     * at JVM startup.
     *
     * @param agentArgs comma-separated key=value configuration (e.g., "port=7758")
     * @param inst      the Instrumentation instance provided by the JVM
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        LOG.info("JoltVM Agent premain invoked (startup attachment)");
        initialize(agentArgs, inst);
    }

    /**
     * Entry point for dynamic attachment.
     *
     * <p>Invoked by the JVM when the agent is loaded dynamically via the Attach API
     * (e.g., through {@link AttachHelper}).
     *
     * @param agentArgs comma-separated key=value configuration (e.g., "port=7758")
     * @param inst      the Instrumentation instance provided by the JVM
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        LOG.info("JoltVM Agent agentmain invoked (dynamic attachment)");
        initialize(agentArgs, inst);
    }

    /**
     * Initializes the JoltVM Agent.
     *
     * <p>This method is idempotent — if the agent has already been initialized,
     * subsequent calls will log a warning and return immediately.
     *
     * @param agentArgs comma-separated key=value configuration
     * @param inst      the Instrumentation instance
     */
    private static synchronized void initialize(String agentArgs, Instrumentation inst) {
        if (initialized) {
            LOG.warning("JoltVM Agent is already initialized, skipping re-initialization");
            return;
        }

        LOG.info(BANNER);

        // Store the Instrumentation instance for global access
        InstrumentationHolder.set(inst);
        initialized = true;

        // Log capabilities
        LOG.info(String.format("JoltVM Agent initialized successfully [version=%s]",
                JoltVMAgent.class.getPackage().getImplementationVersion()));
        LOG.info(String.format("Instrumentation capabilities: redefine=%b, retransform=%b, native-prefix=%b",
                inst.isRedefineClassesSupported(),
                inst.isRetransformClassesSupported(),
                inst.isNativeMethodPrefixSupported()));

        if (agentArgs != null && !agentArgs.isBlank()) {
            LOG.info(String.format("Agent arguments: %s", agentArgs));
        }

        // TODO: Phase 2 — Start embedded Netty web server
        // TODO: Phase 3 — Initialize ClassTransformerManager
        // TODO: Phase 4 — Initialize profiler components
        // TODO: Phase 5 — Detect and inspect Spring context
    }
}
