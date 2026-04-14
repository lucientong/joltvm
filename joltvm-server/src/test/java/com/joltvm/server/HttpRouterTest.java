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

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link HttpRouter}.
 */
class HttpRouterTest {

    @Test
    @DisplayName("matches exact static path")
    void matchesStaticPath() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/health", (req, params) -> null);

        HttpRouter.RouteMatch match = router.match(HttpMethod.GET, "/api/health");
        assertNotNull(match);
        assertTrue(match.pathParams().isEmpty());
    }

    @Test
    @DisplayName("returns null for non-matching path")
    void returnsNullForNonMatch() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/health", (req, params) -> null);

        assertNull(router.match(HttpMethod.GET, "/api/other"));
    }

    @Test
    @DisplayName("returns null for non-matching method")
    void returnsNullForWrongMethod() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/health", (req, params) -> null);

        assertNull(router.match(HttpMethod.POST, "/api/health"));
    }

    @Test
    @DisplayName("extracts single path parameter")
    void extractsSinglePathParam() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/classes/{className}", (req, params) -> null);

        HttpRouter.RouteMatch match = router.match(HttpMethod.GET, "/api/classes/java.lang.String");
        assertNotNull(match);
        assertEquals("java.lang.String", match.pathParams().get("className"));
    }

    @Test
    @DisplayName("extracts multiple path parameters")
    void extractsMultiplePathParams() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/{group}/{name}", (req, params) -> null);

        HttpRouter.RouteMatch match = router.match(HttpMethod.GET, "/api/classes/MyClass");
        assertNotNull(match);
        assertEquals("classes", match.pathParams().get("group"));
        assertEquals("MyClass", match.pathParams().get("name"));
    }

    @Test
    @DisplayName("path parameter with nested pattern")
    void pathParamWithNestedPattern() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/classes/{className}/source", (req, params) -> null);

        HttpRouter.RouteMatch match = router.match(HttpMethod.GET, "/api/classes/com.example.Foo/source");
        assertNotNull(match);
        assertEquals("com.example.Foo", match.pathParams().get("className"));
    }

    @Test
    @DisplayName("first matching route wins")
    void firstMatchWins() {
        HttpRouter router = new HttpRouter();
        RouteHandler first = (req, params) -> HttpResponseHelper.text("first");
        RouteHandler second = (req, params) -> HttpResponseHelper.text("second");

        router.addRoute(HttpMethod.GET, "/api/test", first);
        router.addRoute(HttpMethod.GET, "/api/test", second);

        HttpRouter.RouteMatch match = router.match(HttpMethod.GET, "/api/test");
        assertNotNull(match);
        assertSame(first, match.handler());
    }

    @Test
    @DisplayName("getRoutes returns unmodifiable list")
    void getRoutesIsUnmodifiable() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/test", (req, params) -> null);

        assertThrows(UnsupportedOperationException.class, () ->
                router.getRoutes().add(null));
    }

    @Test
    @DisplayName("addRoute throws on null arguments")
    void addRouteThrowsOnNull() {
        HttpRouter router = new HttpRouter();
        assertThrows(NullPointerException.class, () ->
                router.addRoute(null, "/path", (r, p) -> null));
        assertThrows(NullPointerException.class, () ->
                router.addRoute(HttpMethod.GET, null, (r, p) -> null));
        assertThrows(NullPointerException.class, () ->
                router.addRoute(HttpMethod.GET, "/path", null));
    }

    @Test
    @DisplayName("does not match partial path")
    void doesNotMatchPartialPath() {
        HttpRouter router = new HttpRouter();
        router.addRoute(HttpMethod.GET, "/api/classes", (req, params) -> null);

        assertNull(router.match(HttpMethod.GET, "/api/classes/extra"));
        assertNull(router.match(HttpMethod.GET, "/api"));
    }
}
