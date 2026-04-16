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

package com.joltvm.server.plugin;

import com.joltvm.server.HttpResponseHelper;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RouteDefinitionTest {

    @Test
    void getFactoryCreatesCorrectly() {
        RouteDefinition def = RouteDefinition.get("/status",
                (request, params) -> HttpResponseHelper.json(Map.of("ok", true)));
        assertEquals(HttpMethod.GET, def.method());
        assertEquals("/status", def.path());
        assertNotNull(def.handler());
    }

    @Test
    void postFactoryCreatesCorrectly() {
        RouteDefinition def = RouteDefinition.post("/action",
                (request, params) -> HttpResponseHelper.json(Map.of("done", true)));
        assertEquals(HttpMethod.POST, def.method());
        assertEquals("/action", def.path());
    }
}
