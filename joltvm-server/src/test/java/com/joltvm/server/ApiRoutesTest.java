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
 * Unit tests for {@link ApiRoutes}.
 */
class ApiRoutesTest {

    @Test
    @DisplayName("registerAll registers expected routes")
    void registerAllRegistersRoutes() {
        HttpRouter router = new HttpRouter();
        ApiRoutes.registerAll(router);

        // Should have 4 routes
        assertEquals(4, router.getRoutes().size());

        // Verify specific routes exist
        assertNotNull(router.match(HttpMethod.GET, "/api/health"));
        assertNotNull(router.match(HttpMethod.GET, "/api/classes"));
        assertNotNull(router.match(HttpMethod.GET, "/api/classes/java.lang.String"));
        assertNotNull(router.match(HttpMethod.GET, "/api/classes/java.lang.String/source"));
    }

    @Test
    @DisplayName("non-existent route returns null")
    void nonExistentRouteReturnsNull() {
        HttpRouter router = new HttpRouter();
        ApiRoutes.registerAll(router);

        assertNull(router.match(HttpMethod.GET, "/api/nonexistent"));
        assertNull(router.match(HttpMethod.POST, "/api/health"));
    }
}
