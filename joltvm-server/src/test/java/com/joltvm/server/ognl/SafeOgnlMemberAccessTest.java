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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SafeOgnlMemberAccessTest {

    @Test
    void validateExpressionRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> SafeOgnlMemberAccess.validateExpression(""));
        assertThrows(IllegalArgumentException.class,
                () -> SafeOgnlMemberAccess.validateExpression("   "));
        assertThrows(IllegalArgumentException.class,
                () -> SafeOgnlMemberAccess.validateExpression(null));
    }

    @Test
    void validateExpressionRejectsRuntime() {
        assertThrows(SecurityException.class,
                () -> SafeOgnlMemberAccess.validateExpression("@java.lang.Runtime@getRuntime()"));
    }

    @Test
    void validateExpressionRejectsProcessBuilder() {
        assertThrows(SecurityException.class,
                () -> SafeOgnlMemberAccess.validateExpression("new java.lang.ProcessBuilder()"));
    }

    @Test
    void validateExpressionRejectsMemberAccessOverride() {
        assertThrows(SecurityException.class,
                () -> SafeOgnlMemberAccess.validateExpression("#_memberAccess=something"));
    }

    @Test
    void validateExpressionRejectsOgnlInternals() {
        assertThrows(SecurityException.class,
                () -> SafeOgnlMemberAccess.validateExpression("@ognl.OgnlContext@DEFAULT_MEMBER_ACCESS"));
    }

    @Test
    void validateExpressionRejectsReflectionChain() {
        assertThrows(SecurityException.class,
                () -> SafeOgnlMemberAccess.validateExpression("''.getClass().forName('java.lang.Runtime')"));
    }

    @Test
    void validateExpressionAllowsSafeExpressions() {
        assertDoesNotThrow(() -> SafeOgnlMemberAccess.validateExpression("1 + 1"));
        assertDoesNotThrow(() -> SafeOgnlMemberAccess.validateExpression("\"hello\".toUpperCase()"));
        assertDoesNotThrow(() -> SafeOgnlMemberAccess.validateExpression("{1, 2, 3}"));
    }

    @Test
    void isAccessibleBlocksRuntimeClass() {
        SafeOgnlMemberAccess access = new SafeOgnlMemberAccess();
        try {
            java.lang.reflect.Method execMethod = Runtime.class.getMethod("exec", String.class);
            assertFalse(access.isAccessible(null, Runtime.getRuntime(), execMethod, null));
        } catch (NoSuchMethodException e) {
            fail("exec method should exist on Runtime");
        }
    }

    @Test
    void isAccessibleBlocksSystemClass() {
        SafeOgnlMemberAccess access = new SafeOgnlMemberAccess();
        try {
            java.lang.reflect.Method exitMethod = System.class.getMethod("exit", int.class);
            assertFalse(access.isAccessible(null, null, exitMethod, null));
        } catch (NoSuchMethodException e) {
            fail("exit method should exist on System");
        }
    }

    @Test
    void isAccessibleAllowsStringMethods() {
        SafeOgnlMemberAccess access = new SafeOgnlMemberAccess();
        try {
            java.lang.reflect.Method toUpper = String.class.getMethod("toUpperCase");
            assertTrue(access.isAccessible(null, "test", toUpper, null));
        } catch (NoSuchMethodException e) {
            fail("toUpperCase should exist on String");
        }
    }

    @Test
    void isAccessibleBlocksClassForName() {
        SafeOgnlMemberAccess access = new SafeOgnlMemberAccess();
        try {
            java.lang.reflect.Method forName = Class.class.getMethod("forName", String.class);
            assertFalse(access.isAccessible(null, String.class, forName, null));
        } catch (NoSuchMethodException e) {
            fail("forName should exist on Class");
        }
    }

    @Test
    void isAccessibleAllowsClassGetName() {
        SafeOgnlMemberAccess access = new SafeOgnlMemberAccess();
        try {
            java.lang.reflect.Method getName = Class.class.getMethod("getName");
            assertTrue(access.isAccessible(null, String.class, getName, null));
        } catch (NoSuchMethodException e) {
            fail("getName should exist on Class");
        }
    }
}
