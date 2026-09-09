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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.*;

class TunnelServerTest {

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Test
    void testInvalidPort() {
        assertThrows(IllegalArgumentException.class, () -> new TunnelServer(0));
        assertThrows(IllegalArgumentException.class, () -> new TunnelServer(70000));
    }

    @Test
    void testDefaultPort() {
        assertEquals(8800, TunnelServer.DEFAULT_PORT);
    }

    @Test
    void testServerNotRunningByDefault() {
        TunnelServer server = new TunnelServer(9999);
        assertFalse(server.isRunning());
        assertEquals(9999, server.getPort());
        assertEquals("127.0.0.1", server.getBindAddress());
    }

    @Test
    void testRegistryAndCorrelator() {
        TunnelServer server = new TunnelServer(9999);
        assertNotNull(server.getRegistry());
        assertNotNull(server.getCorrelator());
    }

    @Test
    void testStopIdempotent() {
        TunnelServer server = new TunnelServer(9999);
        // Should not throw even if never started
        server.stop();
        server.stop();
    }

    @Test
    void testAddRegistrationToken() {
        TunnelServer server = new TunnelServer(9999);
        server.addRegistrationToken("my-token");
        assertTrue(server.getRegistry().isValidToken("my-token"));
        assertFalse(server.getRegistry().isValidToken("wrong"));
    }

    @Test
    void testRemoteBindRequiresBothTokenTypes() {
        TunnelServer server = new TunnelServer("0.0.0.0", 9999, null, null, false);
        IllegalStateException error = assertThrows(IllegalStateException.class, server::start);
        assertTrue(error.getMessage().contains("--token"));
        assertFalse(server.isRunning());
    }

    @Test
    void testRemoteBindWithBothTokenTypesStarts() throws Exception {
        TunnelServer server = new TunnelServer(
                "0.0.0.0", findAvailablePort(), null, null, false);
        server.addRegistrationToken("agent-secret");
        server.addAccessToken("http-secret");
        try {
            server.start();
            assertTrue(server.isRunning());
        } finally {
            server.stop();
        }
    }

    @Test
    void testExplicitInsecureRemoteOverrideStarts() throws Exception {
        TunnelServer server = new TunnelServer(
                "0.0.0.0", findAvailablePort(), null, null, true);
        try {
            server.start();
            assertTrue(server.isRunning());
        } finally {
            server.stop();
        }
    }
}
