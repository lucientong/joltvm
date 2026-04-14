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

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IP-based sliding-window login rate limiter.
 *
 * <p>Tracks failed login attempts per source IP address. Once a given IP accumulates
 * {@value #MAX_FAILURES} failures within a {@value #WINDOW_MILLIS}-millisecond window,
 * further attempts from that IP are rejected with HTTP 429 until the window slides past.
 *
 * <p>On a successful login the failure history for that IP is cleared immediately.
 *
 * <p>Thread-safe: per-IP deques are synchronized independently.
 */
public final class LoginRateLimiter {

    /** Maximum consecutive failures allowed within the time window. */
    static final int MAX_FAILURES = 10;

    /** Sliding window duration in milliseconds (5 minutes). */
    static final long WINDOW_MILLIS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<String, Deque<Long>> failureTimes = new ConcurrentHashMap<>();

    /**
     * Checks whether the given IP is currently rate-limited.
     *
     * <p>Also evicts timestamps outside the current window, so this doubles as a
     * lightweight cleanup call.
     *
     * @param clientIp the client IP address
     * @return {@code true} if the IP should be blocked
     */
    public boolean isBlocked(String clientIp) {
        if (clientIp == null) return false;
        Deque<Long> times = failureTimes.get(clientIp);
        if (times == null) return false;
        long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < cutoff) {
                times.pollFirst();
            }
            return times.size() >= MAX_FAILURES;
        }
    }

    /**
     * Records a failed login attempt for the given IP.
     *
     * @param clientIp the client IP address
     */
    public void recordFailure(String clientIp) {
        if (clientIp == null) return;
        Deque<Long> times = failureTimes.computeIfAbsent(clientIp, k -> new ArrayDeque<>());
        synchronized (times) {
            times.addLast(System.currentTimeMillis());
        }
    }

    /**
     * Clears the failure history for the given IP on successful login.
     *
     * @param clientIp the client IP address
     */
    public void recordSuccess(String clientIp) {
        if (clientIp != null) {
            failureTimes.remove(clientIp);
        }
    }

    /**
     * Returns the number of IPs currently tracked (for monitoring/testing).
     *
     * @return tracked IP count
     */
    public int trackedIpCount() {
        return failureTimes.size();
    }
}
