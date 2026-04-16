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

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A single watch observation record capturing method invocation details.
 */
public class WatchRecord {

    private final Instant timestamp;
    private final String className;
    private final String methodName;
    private final String threadName;
    private final long threadId;
    private final String[] args;
    private final String returnValue;
    private final String exceptionType;
    private final String exceptionMessage;
    private final long durationNanos;
    private final String watchPoint; // BEFORE, AFTER, EXCEPTION

    public WatchRecord(Instant timestamp, String className, String methodName,
                       String threadName, long threadId, String[] args,
                       String returnValue, String exceptionType, String exceptionMessage,
                       long durationNanos, String watchPoint) {
        this.timestamp = timestamp;
        this.className = className;
        this.methodName = methodName;
        this.threadName = threadName;
        this.threadId = threadId;
        this.args = args;
        this.returnValue = returnValue;
        this.exceptionType = exceptionType;
        this.exceptionMessage = exceptionMessage;
        this.durationNanos = durationNanos;
        this.watchPoint = watchPoint;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("timestamp", timestamp.toString());
        map.put("className", className);
        map.put("methodName", methodName);
        map.put("threadName", threadName);
        map.put("threadId", threadId);
        map.put("args", args != null ? java.util.List.of(args) : java.util.List.of());
        map.put("returnValue", returnValue);
        if (exceptionType != null) {
            map.put("exceptionType", exceptionType);
            map.put("exceptionMessage", exceptionMessage);
        }
        map.put("durationNanos", durationNanos);
        map.put("durationMs", durationNanos / 1_000_000.0);
        map.put("watchPoint", watchPoint);
        return map;
    }

    public Instant getTimestamp() { return timestamp; }
    public String getClassName() { return className; }
    public String getMethodName() { return methodName; }
    public long getDurationNanos() { return durationNanos; }
    public String getWatchPoint() { return watchPoint; }
}
