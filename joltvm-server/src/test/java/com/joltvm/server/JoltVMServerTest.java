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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JoltVMServer}.
 */
class JoltVMServerTest {

    private JoltVMServer server;

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.stop();
            // Allow time for the OS to release the port
            Thread.sleep(200);
        }
    }

    /** Returns a random available port by binding to port 0. */
    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("default port is 7758")
    void defaultPort() {
        server = new JoltVMServer();
        assertEquals(7758, server.getPort());
    }

    @Test
    @DisplayName("custom port is accepted")
    void customPort() {
        server = new JoltVMServer(9090);
        assertEquals(9090, server.getPort());
    }

    @Test
    @DisplayName("port 0 throws IllegalArgumentException")
    void invalidPortZero() {
        assertThrows(IllegalArgumentException.class, () -> new JoltVMServer(0));
    }

    @Test
    @DisplayName("port -1 throws IllegalArgumentException")
    void invalidPortNegative() {
        assertThrows(IllegalArgumentException.class, () -> new JoltVMServer(-1));
    }

    @Test
    @DisplayName("port 70000 throws IllegalArgumentException")
    void invalidPortTooLarge() {
        assertThrows(IllegalArgumentException.class, () -> new JoltVMServer(70000));
    }

    @Test
    @DisplayName("server is not running before start")
    void notRunningBeforeStart() {
        server = new JoltVMServer();
        assertFalse(server.isRunning());
    }

    @Test
    @DisplayName("server starts and stops successfully")
    void startAndStop() throws Exception {
        int port = findAvailablePort();
        server = new JoltVMServer(port);
        server.start();
        assertTrue(server.isRunning());

        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    @DisplayName("double start is idempotent")
    void doubleStartIsIdempotent() throws Exception {
        int port = findAvailablePort();
        server = new JoltVMServer(port);
        server.start();
        // Should not throw
        server.start();
        assertTrue(server.isRunning());
    }

    @Test
    @DisplayName("double stop is safe")
    void doubleStopIsSafe() throws Exception {
        int port = findAvailablePort();
        server = new JoltVMServer(port);
        server.start();
        server.stop();
        // Should not throw
        server.stop();
        assertFalse(server.isRunning());
    }

    @Test
    @DisplayName("stop without start is safe")
    void stopWithoutStart() {
        server = new JoltVMServer();
        // Should not throw
        server.stop();
    }

    @Test
    @DisplayName("router is not null")
    void routerIsNotNull() {
        server = new JoltVMServer();
        assertNotNull(server.getRouter());
    }
}
