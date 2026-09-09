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

package com.joltvm.tunnel.handler;

import com.joltvm.tunnel.AccessTokenStore;
import com.joltvm.tunnel.AgentRegistry;
import com.joltvm.tunnel.RequestCorrelator;
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

class TunnelHttpHandlerAuthTest {

    private AccessTokenStore tokens;
    private EmbeddedChannel channel;

    @BeforeEach
    void setUp() {
        tokens = new AccessTokenStore();
        tokens.addToken("access-secret");
        channel = new EmbeddedChannel(new TunnelHttpHandler(
                new AgentRegistry(), new RequestCorrelator(), "test", tokens));
    }

    @Test
    void healthAllowsAnonymous() {
        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/tunnel/health"));
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    @Test
    void agentsRequiresAuth() {
        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/tunnel/agents"));
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        response.release();
    }

    @Test
    void agentsAcceptsBearerToken() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/tunnel/agents");
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer access-secret");
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    @Test
    void agentsAcceptsHeaderToken() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/tunnel/agents");
        request.headers().set("X-Tunnel-Access-Token", "access-secret");
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    @Test
    void agentsAcceptsQueryToken() {
        channel.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET,
                "/api/tunnel/agents?accessToken=access-secret"));
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
    }

    @Test
    void agentsRejectsWrongToken() {
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/tunnel/agents");
        request.headers().set(HttpHeaderNames.AUTHORIZATION, "Bearer wrong");
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        response.release();
    }

    @Test
    void unconfiguredStoreAllowsAgents() {
        EmbeddedChannel open = new EmbeddedChannel(new TunnelHttpHandler(
                new AgentRegistry(), new RequestCorrelator(), "test", new AccessTokenStore()));
        open.writeInbound(new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.GET, "/api/tunnel/agents"));
        FullHttpResponse response = open.readOutbound();
        assertNotNull(response);
        assertEquals(HttpResponseStatus.OK, response.status());
        response.release();
        open.finishAndReleaseAll();
    }
}
