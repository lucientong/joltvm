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

package com.joltvm.server.websocket;

import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SubscriptionManagerTest {

    private SubscriptionManager manager;

    @BeforeEach
    void setUp() {
        manager = new SubscriptionManager();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void initialConnectionCountIsZero() {
        assertEquals(0, manager.getConnectionCount());
    }

    @Test
    void connectIncreasesCount() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        assertEquals(1, manager.getConnectionCount());
    }

    @Test
    void disconnectDecreasesCount() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        manager.onDisconnect(ch);
        assertEquals(0, manager.getConnectionCount());
    }

    @Test
    void subscribeToValidChannel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        manager.onMessage(ch, "{\"type\":\"subscribe\",\"channel\":\"threads.top\"}");

        // Should get a "subscribed" response
        TextWebSocketFrame response = ch.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("subscribed"));
        response.release();
    }

    @Test
    void subscribeToInvalidChannelReturnsError() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        manager.onMessage(ch, "{\"type\":\"subscribe\",\"channel\":\"invalid.channel\"}");

        TextWebSocketFrame response = ch.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("error"));
        response.release();
    }

    @Test
    void unsubscribeFromChannel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        manager.onMessage(ch, "{\"type\":\"subscribe\",\"channel\":\"gc.stats\"}");
        ch.readOutbound(); // consume subscribe response

        manager.onMessage(ch, "{\"type\":\"unsubscribe\",\"channel\":\"gc.stats\"}");
        TextWebSocketFrame response = ch.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("unsubscribed"));
        response.release();
    }

    @Test
    void pingReturnsPong() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        manager.onMessage(ch, "{\"type\":\"ping\"}");

        TextWebSocketFrame response = ch.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("pong"));
        response.release();
    }

    @Test
    void invalidJsonReturnsError() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        manager.onMessage(ch, "not valid json{{{");

        TextWebSocketFrame response = ch.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("error"));
        response.release();
    }

    @Test
    void missingTypeReturnsError() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.onConnect(ch);
        manager.onMessage(ch, "{\"channel\":\"threads.top\"}");

        TextWebSocketFrame response = ch.readOutbound();
        assertNotNull(response);
        assertTrue(response.text().contains("error"));
        response.release();
    }

    @Test
    void supportedChannelsContainsExpected() {
        assertTrue(SubscriptionManager.SUPPORTED_CHANNELS.contains("threads.top"));
        assertTrue(SubscriptionManager.SUPPORTED_CHANNELS.contains("gc.stats"));
        assertTrue(SubscriptionManager.SUPPORTED_CHANNELS.contains("jvm.memory"));
    }

    @Test
    void getSubscriptionCountsInitiallyEmpty() {
        Map<String, Integer> counts = manager.getSubscriptionCounts();
        assertTrue(counts.isEmpty());
    }

    @Test
    void registerProviderDoesNotThrow() {
        assertDoesNotThrow(() ->
                manager.registerProvider("threads.top", () -> Map.of("test", true)));
    }
}
