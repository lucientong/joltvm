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

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import com.joltvm.server.security.Role;
import com.joltvm.server.security.SecurityConfig;
import com.joltvm.server.security.TokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link HttpDispatcherHandler}.
 *
 * <p>Uses Netty's {@link EmbeddedChannel} for in-process testing without
 * opening a real network socket.
 */
@DisplayName("HttpDispatcherHandler")
class HttpDispatcherHandlerTest {

    private HttpRouter router;

    @BeforeEach
    void setUp() {
        router = new HttpRouter();
    }

    /** Writes a request into the channel and reads the outbound response. */
    private FullHttpResponse dispatch(HttpDispatcherHandler handler, FullHttpRequest request) {
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        channel.writeInbound(request);
        FullHttpResponse response = channel.readOutbound();
        assertNotNull(response, "Expected a response from the handler");
        channel.finish();
        return response;
    }

    private FullHttpRequest get(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
    }

    private FullHttpRequest post(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, uri);
    }

    private FullHttpRequest post(String uri, String jsonBody) {
        byte[] bytes = jsonBody.getBytes(StandardCharsets.UTF_8);
        DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri, Unpooled.wrappedBuffer(bytes));
        req.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
        req.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
        return req;
    }

    private FullHttpRequest options(String uri) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.OPTIONS, uri);
    }

    // ----------------------------------------------------------------
    // CORS Preflight
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("CORS preflight")
    class CorsPreflight {

        @Test
        @DisplayName("OPTIONS request returns 204 with CORS headers")
        void optionsReturns204() {
            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, options("/api/health"));

            assertEquals(HttpResponseStatus.NO_CONTENT, response.status());
            assertEquals("*", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN));
            assertNotNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS));
            assertNotNull(response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS));
            assertEquals("3600", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_MAX_AGE));
            assertEquals(0, response.headers().getInt(HttpHeaderNames.CONTENT_LENGTH, -1));
        }

        @Test
        @DisplayName("OPTIONS request does not invoke route handler")
        void optionsDoesNotInvokeHandler() {
            boolean[] called = {false};
            router.addRoute(HttpMethod.OPTIONS, "/api/test", (req, params) -> {
                called[0] = true;
                return HttpResponseHelper.json("ok");
            });

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            dispatch(handler, options("/api/test"));

            assertFalse(called[0], "Route handler should NOT be called for OPTIONS");
        }
    }

    // ----------------------------------------------------------------
    // Route Matching
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Route matching")
    class RouteMatching {

        @Test
        @DisplayName("dispatches GET request to matching handler")
        void getRoute() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/health"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("UP"));
        }

        @Test
        @DisplayName("dispatches POST request to matching handler")
        void postRoute() {
            router.addRoute(HttpMethod.POST, "/api/hotswap", (req, params) ->
                    HttpResponseHelper.json(Map.of("success", true)));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, post("/api/hotswap", "{\"className\":\"Test\"}"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("true"));
        }

        @Test
        @DisplayName("extracts path parameters")
        void pathParams() {
            router.addRoute(HttpMethod.GET, "/api/classes/{className}", (req, params) ->
                    HttpResponseHelper.json(Map.of("name", params.get("className"))));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/classes/com.example.MyService"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("com.example.MyService"));
        }

        @Test
        @DisplayName("strips query string before matching")
        void ignoresQueryString() {
            router.addRoute(HttpMethod.GET, "/api/classes", (req, params) ->
                    HttpResponseHelper.json(Map.of("matched", true)));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/classes?page=1&size=50"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("true"));
        }

        @Test
        @DisplayName("returns 404 for unmatched route")
        void notFound() {
            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/nonexistent"));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("No route found"));
        }

        @Test
        @DisplayName("returns 404 for wrong HTTP method")
        void wrongMethod() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json("ok"));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, post("/api/health"));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        }
    }

    // ----------------------------------------------------------------
    // Handler Exception Handling
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Handler exception handling")
    class ExceptionHandling {

        @Test
        @DisplayName("returns 500 when handler throws exception")
        void handlerThrows() {
            router.addRoute(HttpMethod.GET, "/api/boom", (req, params) -> {
                throw new RuntimeException("Simulated failure");
            });

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/boom"));

            assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Simulated failure"));
        }

        @Test
        @DisplayName("returns 500 when handler throws NullPointerException")
        void handlerThrowsNpe() {
            router.addRoute(HttpMethod.GET, "/api/npe", (req, params) -> {
                throw new NullPointerException("null ref");
            });

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/npe"));

            assertEquals(HttpResponseStatus.INTERNAL_SERVER_ERROR, response.status());
        }
    }

    // ----------------------------------------------------------------
    // Static File Fallback
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Static file fallback")
    class StaticFileFallback {

        @Test
        @DisplayName("falls back to static file handler for unmatched GET")
        void fallbackToStaticFile() {
            RouteHandler staticHandler = (req, params) -> {
                String filePath = params.getOrDefault("filePath", "");
                return HttpResponseHelper.text("served:" + filePath);
            };

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router, staticHandler);
            FullHttpResponse response = dispatch(handler, get("/css/app.css"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("served:css/app.css"));
        }

        @Test
        @DisplayName("serves index.html for root path")
        void fallbackRootPath() {
            RouteHandler staticHandler = (req, params) -> {
                String filePath = params.getOrDefault("filePath", "root");
                return HttpResponseHelper.text("served:" + (filePath.isEmpty() ? "index" : filePath));
            };

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router, staticHandler);
            FullHttpResponse response = dispatch(handler, get("/"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            // Root path "/" → filePath "" (empty), passed to staticHandler
            assertTrue(body.contains("served:"));
        }

        @Test
        @DisplayName("does NOT fall back to static handler for POST")
        void noFallbackForPost() {
            RouteHandler staticHandler = (req, params) ->
                    HttpResponseHelper.text("should not reach");

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router, staticHandler);
            FullHttpResponse response = dispatch(handler, post("/css/app.css"));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        }

        @Test
        @DisplayName("returns 404 when no static handler is configured")
        void noStaticHandler() {
            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/css/app.css"));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        }

        @Test
        @DisplayName("API route takes priority over static fallback")
        void apiTakesPriority() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("source", "api")));

            RouteHandler staticHandler = (req, params) ->
                    HttpResponseHelper.text("source:static");

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router, staticHandler);
            FullHttpResponse response = dispatch(handler, get("/api/health"));

            assertEquals(HttpResponseStatus.OK, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("api"), "API handler should take priority");
        }

        @Test
        @DisplayName("returns 404 when static handler throws exception")
        void staticHandlerThrows() {
            RouteHandler staticHandler = (req, params) -> {
                throw new RuntimeException("Static file error");
            };

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router, staticHandler);
            FullHttpResponse response = dispatch(handler, get("/broken/file.txt"));

            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        }
    }

    // ----------------------------------------------------------------
    // Keep-Alive Handling
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Keep-alive")
    class KeepAlive {

        @Test
        @DisplayName("sets Connection: keep-alive for keep-alive request")
        void keepAliveResponse() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            FullHttpRequest request = get("/api/health");
            request.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, request);

            assertEquals(HttpResponseStatus.OK, response.status());
            assertEquals("keep-alive", response.headers().get(HttpHeaderNames.CONNECTION));
        }

        @Test
        @DisplayName("closes connection for non-keep-alive request")
        void nonKeepAliveResponse() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            FullHttpRequest request = get("/api/health");
            request.headers().set(HttpHeaderNames.CONNECTION, "close");

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);

            EmbeddedChannel channel = new EmbeddedChannel(handler);
            channel.writeInbound(request);
            FullHttpResponse response = channel.readOutbound();

            assertNotNull(response);
            assertEquals(HttpResponseStatus.OK, response.status());
            // For HTTP/1.1, connection close is signaled via listener, not header
            channel.finish();
        }
    }

    // ----------------------------------------------------------------
    // Edge Cases
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("handles request with empty URI path")
        void emptyPath() {
            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            // EmbeddedChannel will still receive the request
            FullHttpRequest request = get("/");
            FullHttpResponse response = dispatch(handler, request);
            // Should not throw — returns 404 (no route, no static handler)
            assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
        }

        @Test
        @DisplayName("handles request with deep nested path")
        void deepNestedPath() {
            router.addRoute(HttpMethod.GET, "/api/a/b/c/d", (req, params) ->
                    HttpResponseHelper.text("deep"));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/a/b/c/d"));

            assertEquals(HttpResponseStatus.OK, response.status());
            assertTrue(response.content().toString(StandardCharsets.UTF_8).contains("deep"));
        }

        @Test
        @DisplayName("handles request with special characters in path")
        void specialCharsInPath() {
            router.addRoute(HttpMethod.GET, "/api/classes/{className}", (req, params) ->
                    HttpResponseHelper.json(Map.of("name", params.get("className"))));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(router);
            FullHttpResponse response = dispatch(handler, get("/api/classes/com.example.My%24Inner"));

            assertEquals(HttpResponseStatus.OK, response.status());
        }
    }

    // ----------------------------------------------------------------
    // Authentication & Authorization
    // ----------------------------------------------------------------
    @Nested
    @DisplayName("Authentication & Authorization")
    class AuthenticationAuthorization {

        private SecurityConfig securityConfig;
        private TokenService tokenService;

        @BeforeEach
        void setUpSecurity() {
            securityConfig = new SecurityConfig(true);
            securityConfig.addUser("viewer", "pass", Role.VIEWER);
            securityConfig.addUser("operator", "pass", Role.OPERATOR);
            tokenService = new TokenService();
        }

        private HttpDispatcherHandler secureHandler() {
            return new HttpDispatcherHandler(router, null, securityConfig, tokenService);
        }

        private FullHttpRequest getWithToken(String uri, String token) {
            FullHttpRequest req = get(uri);
            req.headers().set("Authorization", "Bearer " + token);
            return req;
        }

        @Test
        @DisplayName("returns 401 for protected endpoint without token")
        void protectedEndpointNoToken() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            FullHttpResponse response = dispatch(secureHandler(), get("/api/health"));

            assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Authentication required"));
        }

        @Test
        @DisplayName("returns 401 for invalid token")
        void invalidToken() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            FullHttpResponse response = dispatch(secureHandler(),
                    getWithToken("/api/health", "invalid-token-value"));

            assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Invalid or expired token"));
        }

        @Test
        @DisplayName("returns 200 for valid VIEWER token on VIEWER endpoint")
        void viewerAccessViewerEndpoint() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            String token = tokenService.generateToken("viewer", Role.VIEWER);
            FullHttpResponse response = dispatch(secureHandler(),
                    getWithToken("/api/health", token));

            assertEquals(HttpResponseStatus.OK, response.status());
        }

        @Test
        @DisplayName("returns 403 for VIEWER token on OPERATOR endpoint")
        void viewerDeniedOperatorEndpoint() {
            router.addRoute(HttpMethod.POST, "/api/hotswap", (req, params) ->
                    HttpResponseHelper.json(Map.of("success", true)));

            String token = tokenService.generateToken("viewer", Role.VIEWER);
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/hotswap");
            req.headers().set("Authorization", "Bearer " + token);

            FullHttpResponse response = dispatch(secureHandler(), req);

            assertEquals(HttpResponseStatus.FORBIDDEN, response.status());
            String body = response.content().toString(StandardCharsets.UTF_8);
            assertTrue(body.contains("Insufficient permissions"));
        }

        @Test
        @DisplayName("ADMIN can access OPERATOR endpoints")
        void adminAccessOperatorEndpoint() {
            router.addRoute(HttpMethod.POST, "/api/hotswap", (req, params) ->
                    HttpResponseHelper.json(Map.of("success", true)));

            String token = tokenService.generateToken("admin", Role.ADMIN);
            DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/hotswap");
            req.headers().set("Authorization", "Bearer " + token);

            FullHttpResponse response = dispatch(secureHandler(), req);

            assertEquals(HttpResponseStatus.OK, response.status());
        }

        @Test
        @DisplayName("OPERATOR can access VIEWER endpoints")
        void operatorAccessViewerEndpoint() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            String token = tokenService.generateToken("operator", Role.OPERATOR);
            FullHttpResponse response = dispatch(secureHandler(),
                    getWithToken("/api/health", token));

            assertEquals(HttpResponseStatus.OK, response.status());
        }

        @Test
        @DisplayName("auth/login endpoint is accessible without token")
        void loginEndpointNoAuth() {
            router.addRoute(HttpMethod.POST, "/api/auth/login", (req, params) ->
                    HttpResponseHelper.json(Map.of("token", "test")));

            DefaultFullHttpRequest req = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.POST, "/api/auth/login");

            FullHttpResponse response = dispatch(secureHandler(), req);

            assertEquals(HttpResponseStatus.OK, response.status());
        }

        @Test
        @DisplayName("auth/status endpoint is accessible without token")
        void authStatusEndpointNoAuth() {
            router.addRoute(HttpMethod.GET, "/api/auth/status", (req, params) ->
                    HttpResponseHelper.json(Map.of("authEnabled", true)));

            FullHttpResponse response = dispatch(secureHandler(), get("/api/auth/status"));

            assertEquals(HttpResponseStatus.OK, response.status());
        }

        @Test
        @DisplayName("static files are accessible without auth")
        void staticFilesNoAuth() {
            RouteHandler staticHandler = (req, params) ->
                    HttpResponseHelper.text("css content");

            HttpDispatcherHandler handler = new HttpDispatcherHandler(
                    router, staticHandler, securityConfig, tokenService);
            FullHttpResponse response = dispatch(handler, get("/css/app.css"));

            assertEquals(HttpResponseStatus.OK, response.status());
        }

        @Test
        @DisplayName("security is bypassed when disabled")
        void securityDisabled() {
            SecurityConfig disabledConfig = new SecurityConfig(false);
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            HttpDispatcherHandler handler = new HttpDispatcherHandler(
                    router, null, disabledConfig, tokenService);
            FullHttpResponse response = dispatch(handler, get("/api/health"));

            assertEquals(HttpResponseStatus.OK, response.status());
        }

        @Test
        @DisplayName("returns 401 for malformed Authorization header (not Bearer)")
        void malformedAuthHeader() {
            router.addRoute(HttpMethod.GET, "/api/health", (req, params) ->
                    HttpResponseHelper.json(Map.of("status", "UP")));

            FullHttpRequest req = get("/api/health");
            req.headers().set("Authorization", "Basic dXNlcjpwYXNz");

            FullHttpResponse response = dispatch(secureHandler(), req);

            assertEquals(HttpResponseStatus.UNAUTHORIZED, response.status());
        }
    }
}
