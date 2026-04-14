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
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
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
 * the {@link Instrumentation} instance in {@link InstrumentationHolder} and starts the
 * embedded Netty web server (if the server module is on the classpath).
 *
 * @see InstrumentationHolder
 * @see AttachHelper
 */
public final class JoltVMAgent {

    private static final Logger LOG = Logger.getLogger(JoltVMAgent.class.getName());

    /** Default port for the JoltVM embedded web server. */
    static final int DEFAULT_PORT = 7758;

    private static final String SERVER_CLASS = "com.joltvm.server.JoltVMServer";
    private static final String API_ROUTES_CLASS = "com.joltvm.server.APIRoutes";

    private static final String BANNER = """
            
              ╦╔═╗╦  ╔╦╗╦  ╦╔╦╗
              ║║ ║║   ║ ╚╗╔╝║║║
             ╚╝╚═╝╩═╝ ╩  ╚╝ ╩ ╩
             Like a jolt of electricity
            """;

    private static volatile boolean initialized = false;

    /** Holds the server instance (via reflection to avoid compile-time circular dependency). */
    private static Object server;

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
        stopServer();
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

        // Start embedded Netty web server (loaded via reflection to avoid circular deps)
        Map<String, String> parsedArgs = parseArgs(agentArgs);
        int port;
        try {
            port = Integer.parseInt(parsedArgs.getOrDefault("port", String.valueOf(DEFAULT_PORT)));
        } catch (NumberFormatException e) {
            LOG.warning("Invalid port value: " + parsedArgs.get("port") + ", using default: " + DEFAULT_PORT);
            port = DEFAULT_PORT;
        }
        startServer(port, parsedArgs);

        // Spring Boot awareness is handled by SpringContextService
        // (lazy discovery when /api/spring/* endpoints are first accessed)
        LOG.info("Spring Boot awareness enabled (lazy detection on first API access)");
    }

    /**
     * Starts the embedded Netty HTTP server on a daemon thread.
     *
     * <p>Uses reflection to instantiate the server, avoiding a compile-time dependency
     * from joltvm-agent → joltvm-server (which would create a circular dependency
     * since joltvm-server depends on joltvm-agent for InstrumentationHolder).
     *
     * <p>Passes the full {@code agentArgs} map to {@code JoltVMServer} and
     * {@code APIRoutes.registerAll} so all configuration (security, adminPassword,
     * auditFile, etc.) flows through a single path.
     *
     * @param port      the port to listen on
     * @param agentArgs parsed agent argument map
     */
    private static void startServer(int port, Map<String, String> agentArgs) {
        try {
            // Reflective equivalent of:
            //   JoltVMServer server = new JoltVMServer(port, agentArgs);
            //   APIRoutes.registerAll(server.getRouter(), server.getSecurityConfig(),
            //                         server.getTokenService(), agentArgs);
            //   server.start();
            Class<?> serverClass = Class.forName(SERVER_CLASS);
            server = serverClass.getConstructor(int.class, Map.class).newInstance(port, agentArgs);

            // Get router and security objects from server
            Method getRouter = serverClass.getMethod("getRouter");
            Object router = getRouter.invoke(server);

            Object securityConfig = serverClass.getMethod("getSecurityConfig").invoke(server);
            Object tokenService = serverClass.getMethod("getTokenService").invoke(server);

            // Call APIRoutes.registerAll(router, securityConfig, tokenService, agentArgs)
            Class<?> apiRoutesClass = Class.forName(API_ROUTES_CLASS);
            Class<?> routerClass = router.getClass();
            Method registerAll = apiRoutesClass.getMethod("registerAll",
                    routerClass, securityConfig.getClass(), tokenService.getClass(), Map.class);
            registerAll.invoke(null, router, securityConfig, tokenService, agentArgs);

            // Start server on a daemon thread
            Method startMethod = serverClass.getMethod("start");
            Thread serverThread = new Thread(() -> {
                try {
                    startMethod.invoke(server);
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "Failed to start JoltVM server on port " + port, e);
                }
            }, "joltvm-server");
            serverThread.setDaemon(true);
            serverThread.start();

            // Register shutdown hook
            Method stopMethod = serverClass.getMethod("stop");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    stopMethod.invoke(server);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Error stopping JoltVM server", e);
                }
            }, "joltvm-shutdown"));

            LOG.info(String.format("JoltVM Web IDE: http://localhost:%d", port));
            LOG.info(String.format("JoltVM Health:  http://localhost:%d/api/health", port));

        } catch (ClassNotFoundException e) {
            LOG.info("JoltVM server module not found on classpath, skipping HTTP server startup");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to initialize JoltVM server", e);
        }
    }

    /**
     * Stops the embedded server if it is running.
     */
    private static void stopServer() {
        if (server != null) {
            try {
                Method stopMethod = server.getClass().getMethod("stop");
                stopMethod.invoke(server);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error stopping JoltVM server during reset", e);
            }
            server = null;
        }
    }

    /**
     * Parses agent arguments from "key1=val1,key2=val2" format.
     *
     * @param agentArgs the raw agent args string
     * @return a map of key-value pairs
     */
    static Map<String, String> parseArgs(String agentArgs) {
        Map<String, String> result = new HashMap<>();
        if (agentArgs == null || agentArgs.isBlank()) {
            return result;
        }
        for (String pair : agentArgs.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                result.put(kv[0].trim(), kv[1].trim());
            }
        }
        return result;
    }
}
