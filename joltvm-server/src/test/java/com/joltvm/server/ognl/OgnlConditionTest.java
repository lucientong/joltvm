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

package com.joltvm.server.ognl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OgnlConditionTest {

    private OgnlService ognlService;

    @BeforeEach
    void setUp() {
        ognlService = new OgnlService();
    }

    @AfterEach
    void tearDown() {
        ognlService.shutdown();
    }

    @Test
    void compileConditionRejectsBlank() {
        assertThrows(IllegalArgumentException.class, () -> ognlService.compileCondition("  "));
    }

    @Test
    void evaluateConditionCostTrue() {
        Object compiled = ognlService.compileCondition("#cost > 1000000");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("cost", 2_000_000L);
        assertTrue(ognlService.evaluateCondition(compiled, ctx));
    }

    @Test
    void evaluateConditionCostFalse() {
        Object compiled = ognlService.compileCondition("#cost > 1000000");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("cost", 100L);
        assertFalse(ognlService.evaluateCondition(compiled, ctx));
    }

    @Test
    void evaluateConditionArgsAndReturnObj() {
        Object compiled = ognlService.compileCondition("#args[0] == \"hello\" && #returnObj != null");
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("args", new Object[]{"hello"});
        ctx.put("returnObj", "world");
        assertTrue(ognlService.evaluateCondition(compiled, ctx));

        ctx.put("returnObj", null);
        assertFalse(ognlService.evaluateCondition(compiled, ctx));
    }

    @Test
    void evaluateConditionNonBooleanReturnsFalse() {
        Object compiled = ognlService.compileCondition("#cost");
        Map<String, Object> ctx = Map.of("cost", 42L);
        assertFalse(ognlService.evaluateCondition(compiled, ctx));
    }

    @Test
    void evaluateConditionStringOverload() {
        Map<String, Object> ctx = Map.of("throwExp", new RuntimeException("x"));
        assertTrue(ognlService.evaluateCondition("#throwExp != null", ctx));
        assertFalse(ognlService.evaluateCondition("#throwExp != null", Map.of()));
    }

    @Test
    void nullCompiledConditionIsTrue() {
        assertTrue(ognlService.evaluateCondition((Object) null, Map.of()));
    }
}
