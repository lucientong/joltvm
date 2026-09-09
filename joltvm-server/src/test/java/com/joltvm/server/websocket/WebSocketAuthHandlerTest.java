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

import com.joltvm.server.security.Role;
import com.joltvm.server.security.SecurityConfig;
import com.joltvm.server.security.TokenService;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketAuthHandlerTest {

    private TokenService tokenService;
    private SecurityConfig securityEnabled;
    private SecurityConfig securityDisabled;

    @BeforeEach
    void setUp() {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) i;
        }
        tokenService = new TokenService(key, 3600);
        securityEnabled = new SecurityConfig(true);
        securityDisabled = new SecurityConfig(false);
    }

    @Test
    void securityDisabledPassesUpgradeThrough() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebSocketAuthHandler(securityDisabled, tokenService));
        DefaultFullHttpRequest request = upgradeRequest("/ws");
        assertTrue(channel.writeInbound(request));
        assertSame(request, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void securityEnabledRejectsMissingToken() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebSocketAuthHandler(securityEnabled, tokenService));
        channel.writeInbound(upgradeRequest("/ws"));
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        assertNull(channel.readInbound());
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void securityEnabledRejectsInvalidToken() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebSocketAuthHandler(securityEnabled, tokenService));
        channel.writeInbound(upgradeRequest("/ws?token=not-a-valid-token"));
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void securityEnabledAcceptsValidToken() {
        String token = tokenService.generateToken("admin", Role.ADMIN);
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebSocketAuthHandler(securityEnabled, tokenService));
        DefaultFullHttpRequest request = upgradeRequest("/ws?token=" + token);
        assertTrue(channel.writeInbound(request));
        assertSame(request, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    @Test
    void nonWebSocketRequestsPassThrough() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new WebSocketAuthHandler(securityEnabled, tokenService));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/health");
        assertTrue(channel.writeInbound(request));
        assertSame(request, channel.readInbound());
        channel.finishAndReleaseAll();
    }

    private static DefaultFullHttpRequest upgradeRequest(String uri) {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        request.headers().set(HttpHeaderNames.UPGRADE, "websocket");
        request.headers().set(HttpHeaderNames.CONNECTION, "Upgrade");
        return request;
    }
}
