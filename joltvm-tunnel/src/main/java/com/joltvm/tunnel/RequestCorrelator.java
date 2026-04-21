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

package com.joltvm.tunnel;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tracks pending proxied requests and correlates responses.
 *
 * <p>When the tunnel server sends a request to an agent, a {@link CompletableFuture}
 * is stored keyed by requestId. When the agent sends back a response, the future
 * is completed. Stale requests are automatically timed out.
 */
public class RequestCorrelator {

    private static final Logger LOG = Logger.getLogger(RequestCorrelator.class.getName());

    /** Default request timeout in seconds. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;

    /**
     * A proxied response from an agent.
     *
     * @param requestId  correlation ID
     * @param status     HTTP status code
     * @param headers    response headers
     * @param body       response body (may be null)
     */
    public record ProxiedResponse(
            String requestId,
            int status,
            Map<String, String> headers,
            String body
    ) {
        public ProxiedResponse {
            headers = headers != null ? Map.copyOf(headers) : Map.of();
        }
    }

    /** requestId → pending future. */
    private final ConcurrentHashMap<String, CompletableFuture<ProxiedResponse>> pendingRequests
            = new ConcurrentHashMap<>();

    /**
     * Registers a pending request and returns a future that will be completed
     * when the response arrives (or times out).
     *
     * @param requestId the unique request ID
     * @return a future that will hold the proxied response
     */
    public CompletableFuture<ProxiedResponse> registerRequest(String requestId) {
        CompletableFuture<ProxiedResponse> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        // Auto-timeout
        future.orTimeout(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, ex) -> {
                    pendingRequests.remove(requestId);
                    if (ex instanceof TimeoutException) {
                        LOG.log(Level.WARNING, "Request timed out: {0}", requestId);
                    }
                });

        return future;
    }

    /**
     * Completes a pending request with the given response.
     *
     * @param requestId the correlation ID
     * @param response  the proxied response
     * @return true if a pending request was found and completed
     */
    public boolean completeRequest(String requestId, ProxiedResponse response) {
        CompletableFuture<ProxiedResponse> future = pendingRequests.remove(requestId);
        if (future != null) {
            return future.complete(response);
        }
        LOG.log(Level.FINE, "No pending request for ID: {0}", requestId);
        return false;
    }

    /**
     * Cancels all pending requests for a given agent (e.g., on disconnect).
     *
     * @param reason the cancellation reason
     */
    public void cancelAll(String reason) {
        for (Map.Entry<String, CompletableFuture<ProxiedResponse>> entry : pendingRequests.entrySet()) {
            entry.getValue().completeExceptionally(
                    new RuntimeException("Request cancelled: " + reason));
        }
        pendingRequests.clear();
    }

    /**
     * Returns the number of pending requests.
     *
     * @return pending request count
     */
    public int getPendingCount() {
        return pendingRequests.size();
    }
}
