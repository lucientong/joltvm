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

package com.joltvm.tunnel;

import java.util.logging.Logger;

/**
 * Entry point for the standalone JoltVM Tunnel Server.
 *
 * <p>Usage:
 * <pre>
 *   java -jar joltvm-tunnel-*-all.jar [options]
 *
 *   Options:
 *     --bind-address=127.0.0.1
 *     --port=8800           Server port (default: 8800)
 *     --token=SECRET        Registration token (repeatable, if none: allow all)
 *     --access-token=SECRET HTTP dashboard/proxy token (repeatable)
 *     --allow-insecure-remote
 *     --tls-cert=cert.pem   TLS certificate path
 *     --tls-key=key.pem     TLS private key path
 * </pre>
 */
public final class TunnelServerMain {

    private static final Logger LOG = Logger.getLogger(TunnelServerMain.class.getName());

    private static final String BANNER = """

              ╦╔═╗╦  ╔╦╗╦  ╦╔╦╗  ╔╦╗╦ ╦╔╗╔╔╗╔╔═╗╦
              ║║ ║║   ║ ╚╗╔╝║║║   ║ ║ ║║║║║║║║╣ ║
             ╚╝╚═╝╩═╝ ╩  ╚╝ ╩ ╩   ╩ ╚═╝╝╚╝╝╚╝╚═╝╩═╝
             Remote diagnostics tunnel server
            """;

    private TunnelServerMain() {}

    public static void main(String[] args) throws Exception {
        LOG.info(BANNER);

        int port = TunnelServer.DEFAULT_PORT;
        String bindAddress = TunnelServer.DEFAULT_BIND_ADDRESS;
        String tlsCert = null;
        String tlsKey = null;
        boolean allowInsecureRemote = false;
        java.util.List<String> tokens = new java.util.ArrayList<>();
        java.util.List<String> accessTokens = new java.util.ArrayList<>();

        for (String arg : args) {
            if (arg.startsWith("--bind-address=")) {
                bindAddress = arg.substring("--bind-address=".length());
            } else if (arg.startsWith("--port=")) {
                port = Integer.parseInt(arg.substring("--port=".length()));
            } else if (arg.startsWith("--token=")) {
                tokens.add(arg.substring("--token=".length()));
            } else if (arg.startsWith("--access-token=")) {
                accessTokens.add(arg.substring("--access-token=".length()));
            } else if (arg.startsWith("--tls-cert=")) {
                tlsCert = arg.substring("--tls-cert=".length());
            } else if (arg.startsWith("--tls-key=")) {
                tlsKey = arg.substring("--tls-key=".length());
            } else if ("--allow-insecure-remote".equals(arg)) {
                allowInsecureRemote = true;
            } else if ("--help".equals(arg) || "-h".equals(arg)) {
                printUsage();
                return;
            } else {
                System.err.println("Unknown argument: " + arg);
                printUsage();
                System.exit(1);
            }
        }

        TunnelServer server = new TunnelServer(
                bindAddress, port, tlsCert, tlsKey, allowInsecureRemote);
        for (String token : tokens) {
            server.addRegistrationToken(token);
        }
        for (String token : accessTokens) {
            server.addAccessToken(token);
        }

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "tunnel-shutdown"));

        server.start();

        LOG.info("Tunnel server is running. Press Ctrl+C to stop.");
        if (tokens.isEmpty()) {
            LOG.warning("No registration tokens configured — any local agent can connect (dev mode).");
        } else {
            LOG.info(tokens.size() + " registration token(s) configured.");
        }
        if (accessTokens.isEmpty()) {
            LOG.warning("No --access-token configured — local HTTP dashboard/proxy APIs are open "
                    + "(dev mode). Configure --access-token for remote access.");
        } else {
            LOG.info(accessTokens.size() + " HTTP access token(s) configured.");
        }

        // Block main thread
        Thread.currentThread().join();
    }

    private static void printUsage() {
        System.out.println("""
                JoltVM Tunnel Server

                Usage: java -jar joltvm-tunnel-*-all.jar [options]

                Options:
                  --bind-address=ADDRESS   Bind address (default: 127.0.0.1)
                  --port=PORT              Server port (default: 8800)
                  --token=SECRET           Agent registration token (repeatable)
                  --access-token=SECRET    HTTP dashboard/proxy access token (repeatable)
                  --allow-insecure-remote  Allow remote bind without both tokens (dangerous)
                  --tls-cert=PATH          TLS certificate (PEM)
                  --tls-key=PATH           TLS private key (PEM)
                  --help, -h               Show this help
                """);
    }
}
