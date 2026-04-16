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

package com.joltvm.server.watch;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * Byte Buddy Advice class for watch method observation.
 *
 * <p>Injected into watched methods to capture invocation details and
 * forward them to the {@link WatchService} for recording.
 */
public class WatchAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter long startTime,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown,
            @Advice.Origin("#m") String methodName,
            @Advice.Origin("#t") String declaringType,
            @Advice.AllArguments Object[] arguments) {

        long durationNanos = System.nanoTime() - startTime;

        String[] argStrings = null;
        if (arguments != null) {
            argStrings = new String[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                argStrings[i] = truncate(safeToString(arguments[i]), 200);
            }
        }

        String returnStr = (thrown == null && returnValue != null)
                ? truncate(safeToString(returnValue), 200) : null;
        String exType = thrown != null ? thrown.getClass().getName() : null;
        String exMsg = thrown != null ? truncate(safeToString(thrown.getMessage()), 200) : null;

        WatchService.recordInvocation(declaringType, methodName,
                argStrings, returnStr, exType, exMsg, durationNanos);
    }

    private static String safeToString(Object obj) {
        if (obj == null) return "null";
        try {
            return obj.toString();
        } catch (Exception e) {
            return obj.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(obj));
        }
    }

    private static String truncate(String str, int maxLen) {
        if (str == null) return null;
        return str.length() > maxLen ? str.substring(0, maxLen) + "..." : str;
    }
}
