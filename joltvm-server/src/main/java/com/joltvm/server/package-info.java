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
 * JoltVM Server module.
 *
 * <p>Provides an embedded Netty HTTP/WebSocket server that runs inside the target JVM
 * (alongside the agent). Exposes REST APIs for the Web IDE frontend to interact with
 * the JVM: list classes, decompile source, view class details, etc.
 *
 * <h3>API Endpoints:</h3>
 * <ul>
 *   <li>{@code GET /api/health} — Health check with JVM info</li>
 *   <li>{@code GET /api/classes} — List loaded classes (paginated, filterable)</li>
 *   <li>{@code GET /api/classes/{className}} — Class detail (fields, methods, metadata)</li>
 *   <li>{@code GET /api/classes/{className}/source} — Decompiled Java source code</li>
 * </ul>
 *
 * @see com.joltvm.server.JoltVMServer
 * @see com.joltvm.server.APIRoutes
 * @see com.joltvm.agent.JoltVMAgent
 */
package com.joltvm.server;
