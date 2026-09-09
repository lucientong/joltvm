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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelatorTest {

    private RequestCorrelator correlator;

    @BeforeEach
    void setUp() {
        correlator = new RequestCorrelator();
    }

    @Test
    void testRegisterAndComplete() throws Exception {
        CompletableFuture<RequestCorrelator.ProxiedResponse> future =
                correlator.registerRequest("req-1");
        assertEquals(1, correlator.getPendingCount());

        RequestCorrelator.ProxiedResponse response = new RequestCorrelator.ProxiedResponse(
                "req-1", 200, Map.of("Content-Type", "application/json"), "{\"ok\":true}");
        assertTrue(correlator.completeRequest("req-1", response));

        RequestCorrelator.ProxiedResponse result = future.get(1, TimeUnit.SECONDS);
        assertEquals(200, result.status());
        assertEquals("{\"ok\":true}", result.body());
        assertEquals(0, correlator.getPendingCount());
    }

    @Test
    void testCompleteUnknownRequest() {
        RequestCorrelator.ProxiedResponse response = new RequestCorrelator.ProxiedResponse(
                "unknown", 200, Map.of(), null);
        assertFalse(correlator.completeRequest("unknown", response));
    }

    @Test
    void testCancelAll() {
        CompletableFuture<RequestCorrelator.ProxiedResponse> f1 = correlator.registerRequest("r1");
        CompletableFuture<RequestCorrelator.ProxiedResponse> f2 = correlator.registerRequest("r2");

        correlator.cancelAll("server shutdown");

        assertTrue(f1.isCompletedExceptionally());
        assertTrue(f2.isCompletedExceptionally());
        assertEquals(0, correlator.getPendingCount());
    }

    @Test
    void testPendingCount() {
        assertEquals(0, correlator.getPendingCount());
        correlator.registerRequest("r1");
        correlator.registerRequest("r2");
        assertEquals(2, correlator.getPendingCount());
    }

    @Test
    void testProxiedResponseImmutableHeaders() {
        RequestCorrelator.ProxiedResponse response = new RequestCorrelator.ProxiedResponse(
                "r1", 200, Map.of("key", "value"), "body");
        assertEquals("value", response.headers().get("key"));
        assertThrows(UnsupportedOperationException.class,
                () -> response.headers().put("new", "val"));
    }

    @Test
    void testProxiedResponseNullHeaders() {
        RequestCorrelator.ProxiedResponse response = new RequestCorrelator.ProxiedResponse(
                "r1", 200, null, null);
        assertNotNull(response.headers());
        assertTrue(response.headers().isEmpty());
        assertNull(response.body());
    }

    @Test
    void testCancelForAgentOnlyAffectsThatAgent() {
        CompletableFuture<RequestCorrelator.ProxiedResponse> a1 =
                correlator.registerRequest("r1", "agent-a");
        CompletableFuture<RequestCorrelator.ProxiedResponse> a2 =
                correlator.registerRequest("r2", "agent-a");
        CompletableFuture<RequestCorrelator.ProxiedResponse> b1 =
                correlator.registerRequest("r3", "agent-b");

        correlator.cancelForAgent("agent-a", "disconnected");

        assertTrue(a1.isCompletedExceptionally());
        assertTrue(a2.isCompletedExceptionally());
        assertFalse(b1.isDone());
        assertEquals(1, correlator.getPendingCount());

        assertTrue(correlator.completeRequest("r3",
                new RequestCorrelator.ProxiedResponse("r3", 200, Map.of(), "ok")));
        assertEquals("ok", b1.join().body());
    }
}
