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
import io.netty.handler.stream.ChunkedWriteHandler;

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
    private final AtomicBoolean running = new AtomicBoolean(false);

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    /**
     * Creates a server on the default port ({@value DEFAULT_PORT}).
     */
    public JoltVMServer() {
        this(DEFAULT_PORT);
    }

    /**
     * Creates a server on the specified port.
     *
     * @param port the TCP port to listen on (must be 1–65535)
     * @throws IllegalArgumentException if port is out of range
     */
    public JoltVMServer(int port) {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }
        this.port = port;
        this.router = new HttpRouter();
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
                            ch.pipeline().addLast(
                                    new HttpServerCodec(),
                                    new HttpObjectAggregator(MAX_CONTENT_LENGTH),
                                    new ChunkedWriteHandler(),
                                    new HttpDispatcherHandler(router)
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
