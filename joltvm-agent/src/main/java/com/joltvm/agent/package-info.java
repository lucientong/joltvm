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

/**
 * JoltVM Agent core module.
 *
 * <p>Provides the Java Agent entry points ({@link com.joltvm.agent.JoltVMAgent#premain premain}
 * and {@link com.joltvm.agent.JoltVMAgent#agentmain agentmain}), the global
 * {@link com.joltvm.agent.InstrumentationHolder InstrumentationHolder} for accessing the
 * {@link java.lang.instrument.Instrumentation} instance, and the
 * {@link com.joltvm.agent.AttachHelper AttachHelper} for dynamically attaching the agent
 * to running JVM processes via the Attach API.
 *
 * <p>This package serves as the foundation for all other JoltVM modules (transformer,
 * profiler, server, spring) which depend on the Instrumentation instance managed here.
 *
 * @see com.joltvm.agent.JoltVMAgent
 * @see com.joltvm.agent.InstrumentationHolder
 * @see com.joltvm.agent.AttachHelper
 */
package com.joltvm.agent;
