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
 * Package providing security features for JoltVM:
 *
 * <ul>
 *   <li>{@code Role} — RBAC roles (Viewer / Operator / Admin)</li>
 *   <li>{@code TokenService} — HMAC-SHA256 token generation and validation</li>
 *   <li>{@code SecurityConfig} — Authentication configuration and user management</li>
 *   <li>{@code RoutePermissions} — API endpoint to role mapping</li>
 *   <li>{@code AuditLogService} — Persistent audit log with export</li>
 * </ul>
 *
 * <p>Security can be enabled/disabled via {@code SecurityConfig.setEnabled(boolean)}.
 * When disabled, all requests are allowed without authentication.
 */
package com.joltvm.server.security;
