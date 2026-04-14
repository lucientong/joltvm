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

package com.joltvm.server.hotswap;

import java.time.Instant;

/**
 * Immutable record of a hot-swap operation for audit/history tracking.
 *
 * @param id        unique ID of this record
 * @param className the fully qualified class name that was hot-swapped
 * @param action    the action performed (HOTSWAP or ROLLBACK)
 * @param status    the result (SUCCESS or FAILED)
 * @param message   additional message (e.g., error info)
 * @param timestamp when the operation was performed
 * @param operator  the user who performed the operation (null if auth disabled)
 * @param reason    the reason for the operation (optional, user-provided)
 * @param diff      summary of bytecode changes (optional, auto-generated)
 */
public record HotSwapRecord(
        String id,
        String className,
        Action action,
        Status status,
        String message,
        Instant timestamp,
        String operator,
        String reason,
        String diff
) {

    /**
     * Backward-compatible constructor without operator/reason/diff fields.
     */
    public HotSwapRecord(String id, String className, Action action,
                         Status status, String message, Instant timestamp) {
        this(id, className, action, status, message, timestamp, null, null, null);
    }

    public enum Action {
        HOTSWAP, ROLLBACK
    }

    public enum Status {
        SUCCESS, FAILED
    }
}
