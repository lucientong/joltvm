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

package com.joltvm.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.stream.ChunkedWriteHandler;

import com.joltvm.server.handler.StaticFileHandler;
import com.joltvm.server.security.SecurityConfig;
import com.joltvm.server.security.TokenService;
import com.joltvm.server.tracing.MethodTraceService;

import java.io.File;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Embedded Netty HTTP server for JoltVM diagnostics.
 *
 * <p>Runs inside the target JVM alongside the agent, providing REST APIs
 * for listing loaded classes, decompiling source code, and more.
 *
 * <p>Thread model (minimal footprint):
 * <ul>
 *   <li>Boss group: 1 thread — accepts incoming connections</li>
 *   <li>Worker group: 2 threads — handles HTTP request/response I/O</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   JoltVMServer server = new JoltVMServer(7758);
 *   server.start();
 *   // ... later ...
 *   server.stop();
 * </pre>
 *
 * @see HttpRouter
 */
public final class JoltVMServer {

    private static final Logger LOG = Logger.getLogger(JoltVMServer.class.getName());

    /** Default port for the JoltVM web server. */
    public static final int DEFAULT_PORT = 7758;

    private static final int BOSS_THREADS = 1;
    private static final int WORKER_THREADS = 2;
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024; // 10 MB

    private final int port;
    private final HttpRouter router;
    private final StaticFileHandler staticFileHandler;
    private final SecurityConfig securityConfig;
    private final TokenService tokenService;

    /**
     * Optional TLS context. Non-null when {@code tlsCert} + {@code tlsKey} agent args are provided.
     * When set, the Netty pipeline is prefixed with an {@code SslHandler} enabling HTTPS.
     */
    private final SslContext sslContext;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * Creates a server on the default port ({@value DEFAULT_PORT}) with security disabled.
     */
    public JoltVMServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Creates a server on the specified port with security disabled.
     *
     * @param port the TCP port to listen on (must be 1–65535)
     * @throws IllegalArgumentException if port is out of range
     */
    public JoltVMServer(int port) {
        this(port, new SecurityConfig(), new TokenService());
    }

    /**
     * Creates a server configured from agent argument key-value pairs.
     *
     * <p>Supported keys:
     * <table>
     *   <tr><th>Key</th><th>Default</th><th>Description</th></tr>
     *   <tr><td>{@code security}</td><td>{@code false}</td><td>Enable authentication ({@code true}/{@code false})</td></tr>
     *   <tr><td>{@code adminPassword}</td><td>{@code joltvm}</td><td>Initial admin password (stored as salted SHA-256 hash)</td></tr>
     * </table>
     *
     * @param port      the TCP port to listen on (must be 1–65535)
     * @param agentArgs parsed agent argument map (may be empty, never null)
     * @throws IllegalArgumentException if port is out of range
     */
    public JoltVMServer(int port, Map<String, String> agentArgs) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        this.port = port;
        this.router = new HttpRouter();
        this.staticFileHandler = new StaticFileHandler();
        boolean secEnabled = "true".equalsIgnoreCase(
                agentArgs.getOrDefault("security", "false"));
        String adminPwd = agentArgs.getOrDefault("adminPassword",
                SecurityConfig.DEFAULT_ADMIN_PASSWORD);
        this.securityConfig = new SecurityConfig(secEnabled, adminPwd);
        this.tokenService = new TokenService();
        this.sslContext = buildSslContext(agentArgs.get("tlsCert"), agentArgs.get("tlsKey"));
    }

    /**
     * Creates a server with explicit security configuration.
     *
     * @param port           the TCP port to listen on
     * @param securityConfig the security configuration
     * @param tokenService   the token service
     */
    public JoltVMServer(int port, SecurityConfig securityConfig, TokenService tokenService) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        this.port = port;
        this.router = new HttpRouter();
        this.staticFileHandler = new StaticFileHandler();
        this.securityConfig = Objects.requireNonNull(securityConfig, "securityConfig");
        this.tokenService = Objects.requireNonNull(tokenService, "tokenService");
        this.sslContext = null;
    }

    /**
     * Starts the HTTP server asynchronously.
     *
     * <p>This method is idempotent — calling it when the server is already
     * running will log a warning and return immediately.
     *
     * @throws Exception if the server cannot bind to the configured port
     */
    public void start() throws Exception {
        if (!running.compareAndSet(false, true)) {
            LOG.warning("JoltVM server is already running on port " + port);
            return;
        }

        bossGroup = new NioEventLoopGroup(BOSS_THREADS);
        workerGroup = new NioEventLoopGroup(WORKER_THREADS);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            if (sslContext != null) {
                                ch.pipeline().addFirst(sslContext.newHandler(ch.alloc()));
                            }
                            ch.pipeline().addLast(
                                    new HttpServerCodec(),
                                    new HttpObjectAggregator(MAX_CONTENT_LENGTH),
                                    new ChunkedWriteHandler(),
                                    new HttpDispatcherHandler(router, staticFileHandler,
                                            securityConfig, tokenService)
                            );
                        }
                    });

            serverChannel = bootstrap.bind(port).sync().channel();
            LOG.info(String.format("JoltVM server started on port %d — http://localhost:%d", port, port));
        } catch (Exception e) {
            running.set(false);
            shutdown(bossGroup, workerGroup);
            throw e;
        }
    }

    /**
     * Stops the HTTP server and releases all resources.
     *
     * <p>This method is idempotent and safe to call even if the server
     * has not been started.
     */
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        LOG.info("Stopping JoltVM server...");

        // Stop any active tracing/sampling before shutting down Netty threads
        MethodTraceService traceService = APIRoutes.getTraceService();
        if (traceService != null) {
            try {
                traceService.stopAll();
                LOG.info("MethodTraceService stopped");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error stopping MethodTraceService", e);
            }
        }

        if (serverChannel != null) {
            try {
                serverChannel.close().sync();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "Interrupted while closing server channel", e);
            }
        }

        shutdown(bossGroup, workerGroup);
        LOG.info("JoltVM server stopped");
    }

    /**
     * Returns whether the server is currently running.
     *
     * @return {@code true} if the server is running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Returns the port the server is configured to listen on.
     *
     * @return the configured port
     */
    public int getPort() {
        return port;
    }

    /**
     * Returns the HTTP router for registering custom handlers.
     *
     * @return the router instance
     */
    public HttpRouter getRouter() {
        return router;
    }

    /**
     * Returns the security configuration.
     *
     * @return the security config
     */
    public SecurityConfig getSecurityConfig() {
        return securityConfig;
    }

    /**
     * Returns the token service.
     *
     * @return the token service
     */
    public TokenService getTokenService() {
        return tokenService;
    }

    /**
     * Returns whether TLS is enabled (i.e., both {@code tlsCert} and {@code tlsKey} were provided).
     *
     * @return {@code true} if TLS is active
     */
    public boolean isTlsEnabled() {
        return sslContext != null;
    }

    /**
     * Builds an {@link SslContext} from the given certificate and key files.
     *
     * @param certPath path to the PEM certificate file, or {@code null}
     * @param keyPath  path to the PEM private key file, or {@code null}
     * @return an {@link SslContext}, or {@code null} if either path is absent
     */
    private static SslContext buildSslContext(String certPath, String keyPath) {
        if (certPath == null || keyPath == null) return null;
        try {
            return SslContextBuilder
                    .forServer(new File(certPath), new File(keyPath))
                    .build();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to build TLS context from cert=" + certPath + " key=" + keyPath, e);
        }
    }

    private static void shutdown(EventLoopGroup... groups) {
        for (EventLoopGroup group : groups) {
            if (group != null) {
                try {
                    group.shutdownGracefully().sync();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
