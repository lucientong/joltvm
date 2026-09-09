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
 * <p>Injected into watched methods. Performs only lightweight capture and
 * forwards raw values to {@link WatchService} via a static bridge — never
 * evaluates OGNL here (Advice may run under the target ClassLoader).
 */
public class WatchAdvice {

    @Advice.OnMethodEnter
    public static long onEnter() {
        return System.nanoTime();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onExit(
            @Advice.Enter long startTime,
            @Advice.This(optional = true) Object target,
            @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
            @Advice.Thrown Throwable thrown,
            @Advice.Origin Class<?> clazz,
            @Advice.Origin("#m") String methodName,
            @Advice.AllArguments Object[] arguments) {

        long durationNanos = System.nanoTime() - startTime;
        String className = clazz != null ? clazz.getName() : null;

        WatchService.recordInvocation(className, methodName,
                arguments, returnValue, thrown, target, clazz, durationNanos);
    }
}
