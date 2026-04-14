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

package com.joltvm.server.security;

/**
 * Role-based access control (RBAC) roles for JoltVM security.
 *
 * <p>Roles are hierarchical: Admin &gt; Operator &gt; Viewer.
 * Each role includes the permissions of all lower roles.
 *
 * <ul>
 *   <li><b>VIEWER</b> — read-only access: health, class browsing, trace viewing, Spring introspection, audit log viewing</li>
 *   <li><b>OPERATOR</b> — Viewer + hot-fix operations: compile, hot-swap, rollback, trace start/stop</li>
 *   <li><b>ADMIN</b> — full access: all operations + user management + audit export</li>
 * </ul>
 */
public enum Role {

    /** Read-only access to all diagnostic views. */
    VIEWER(1),

    /** Viewer + hot-fix, trace, and compile operations. */
    OPERATOR(2),

    /** Full access including user management and audit export. */
    ADMIN(3);

    private final int level;

    Role(int level) {
        this.level = level;
    }

    /**
     * Returns the numeric privilege level (higher = more permissions).
     *
     * @return the privilege level
     */
    public int level() {
        return level;
    }

    /**
     * Checks if this role has at least the permissions of the required role.
     *
     * @param required the minimum required role
     * @return {@code true} if this role's level &ge; required level
     */
    public boolean hasPermission(Role required) {
        return this.level >= required.level;
    }
}
