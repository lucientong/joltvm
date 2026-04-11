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

package com.joltvm.agent;

import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe singleton holder for the {@link Instrumentation} instance.
 *
 * <p>The Instrumentation instance is provided by the JVM when the agent is loaded
 * (via premain or agentmain). This class stores it for global access by other
 * JoltVM modules (transformer, profiler, server, etc.).
 *
 * <p>Design decisions:
 * <ul>
 *   <li>Uses {@link AtomicReference} with CAS for lock-free, thread-safe storage</li>
 *   <li>Only allows setting the instance once — subsequent calls are no-ops with a warning</li>
 *   <li>{@link #get()} throws if the instance has not been set, failing fast rather than
 *       returning null to prevent subtle NPEs in downstream code</li>
 * </ul>
 *
 * @see JoltVMAgent
 */
public final class InstrumentationHolder {

    private static final AtomicReference<Instrumentation> INSTANCE = new AtomicReference<>();

    private InstrumentationHolder() {
        // Utility class — no instantiation
    }

    /**
     * Stores the Instrumentation instance.
     *
     * <p>This method uses CAS (Compare-And-Swap) to ensure the instance is only set once.
     * If an instance has already been stored, this method logs a warning and returns
     * without modifying the stored value.
     *
     * @param inst the Instrumentation instance provided by the JVM; must not be null
     * @throws NullPointerException if inst is null
     */
    public static void set(Instrumentation inst) {
        if (inst == null) {
            throw new NullPointerException("Instrumentation instance must not be null");
        }
        if (!INSTANCE.compareAndSet(null, inst)) {
            // Already set — this is expected when premain and agentmain are both triggered,
            // or when the agent is attached multiple times. Log but do not throw.
            java.util.logging.Logger.getLogger(InstrumentationHolder.class.getName())
                    .warning("Instrumentation instance has already been set, ignoring duplicate set call");
        }
    }

    /**
     * Returns the stored Instrumentation instance.
     *
     * @return the Instrumentation instance, never null
     * @throws IllegalStateException if the instance has not been set yet
     */
    public static Instrumentation get() {
        Instrumentation inst = INSTANCE.get();
        if (inst == null) {
            throw new IllegalStateException(
                    "Instrumentation not available. Ensure the JoltVM agent has been loaded "
                            + "via -javaagent or the Attach API before accessing Instrumentation.");
        }
        return inst;
    }

    /**
     * Checks whether the Instrumentation instance has been set.
     *
     * @return true if the Instrumentation instance is available, false otherwise
     */
    public static boolean isAvailable() {
        return INSTANCE.get() != null;
    }

    /**
     * Resets the holder to its initial state.
     *
     * <p><b>WARNING:</b> This method is intended for testing purposes only.
     * Do not call in production code.
     */
    public static void reset() {
        INSTANCE.set(null);
    }
}
