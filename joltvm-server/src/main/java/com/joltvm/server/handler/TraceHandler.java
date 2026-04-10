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

package com.joltvm.server.handler;

import com.joltvm.server.HttpResponseHelper;
import com.joltvm.server.RouteHandler;
import com.joltvm.server.tracing.MethodTraceService;
import com.joltvm.server.tracing.TracingException;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for trace start/stop endpoints.
 *
 * <p>{@code POST /api/trace/start} — Starts method tracing or stack sampling.
 * <p>{@code POST /api/trace/stop} — Stops the active trace/sampling.
 *
 * <h3>Start trace request body:</h3>
 * <pre>
 * {
 *   "type": "trace",           // "trace" or "sample"
 *   "className": "com.example.MyService",  // required for "trace"
 *   "methodName": "handleRequest",         // optional, "*" for all methods
 *   "duration": 30,                        // seconds (default: 30, max: 300)
 *   "interval": 10                         // sampling interval in ms (for "sample" type)
 * }
 * </pre>
 *
 * <h3>Stop request:</h3>
 * <pre>
 * {
 *   "type": "trace"    // optional: "trace", "sample", or "all" (default: "all")
 * }
 * </pre>
 */
public class TraceHandler implements RouteHandler {

    private static final Logger LOG = Logger.getLogger(TraceHandler.class.getName());

    private final MethodTraceService traceService;

    public TraceHandler(MethodTraceService traceService) {
        this.traceService = traceService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        // Determine action from URI path
        String uri = new QueryStringDecoder(request.uri()).path();
        if (uri.endsWith("/start")) {
            return handleStart(request);
        } else if (uri.endsWith("/stop")) {
            return handleStop(request);
        }
        return HttpResponseHelper.notFound("Unknown trace action. Use /api/trace/start or /api/trace/stop");
    }

    private FullHttpResponse handleStart(FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Request body is required. Expected JSON with 'type' field ('trace' or 'sample').");
        }

        Map<?, ?> bodyMap;
        try {
            bodyMap = HttpResponseHelper.gson().fromJson(body, Map.class);
        } catch (Exception e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Invalid JSON body: " + e.getMessage());
        }

        String type = (String) bodyMap.get("type");
        if (type == null || type.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'type' is required ('trace' or 'sample')");
        }

        try {
            if ("trace".equalsIgnoreCase(type)) {
                return startTrace(bodyMap);
            } else if ("sample".equalsIgnoreCase(type)) {
                return startSampling(bodyMap);
            } else {
                return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                        "Invalid type: '" + type + "'. Must be 'trace' or 'sample'.");
            }
        } catch (TracingException e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to start tracing", e);
            return HttpResponseHelper.error(HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "Failed to start tracing: " + e.getMessage());
        }
    }

    private FullHttpResponse startTrace(Map<?, ?> bodyMap) {
        String className = (String) bodyMap.get("className");
        if (className == null || className.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'className' is required for trace type");
        }

        String methodName = (String) bodyMap.get("methodName");
        int duration = getIntField(bodyMap, "duration", 30);

        traceService.startTrace(className, methodName, duration);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("type", "trace");
        response.put("target", traceService.getCurrentTraceTarget());
        response.put("duration", duration);
        response.put("message", "Method tracing started");

        return HttpResponseHelper.json(response);
    }

    private FullHttpResponse startSampling(Map<?, ?> bodyMap) {
        int interval = getIntField(bodyMap, "interval", 10);
        int duration = getIntField(bodyMap, "duration", 30);

        traceService.startSampling(interval, duration);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("type", "sample");
        response.put("interval", interval);
        response.put("duration", duration);
        response.put("message", "Stack sampling started");

        return HttpResponseHelper.json(response);
    }

    private FullHttpResponse handleStop(FullHttpRequest request) {
        String body = request.content().toString(StandardCharsets.UTF_8);

        String type = "all";
        if (!body.isBlank()) {
            try {
                Map<?, ?> bodyMap = HttpResponseHelper.gson().fromJson(body, Map.class);
                String t = (String) bodyMap.get("type");
                if (t != null && !t.isBlank()) {
                    type = t;
                }
            } catch (Exception e) {
                // Ignore parse errors for stop — just stop all
            }
        }

        boolean stoppedTrace = false;
        boolean stoppedSampling = false;

        if ("trace".equalsIgnoreCase(type) || "all".equalsIgnoreCase(type)) {
            if (traceService.isTracing()) {
                traceService.stopTrace();
                stoppedTrace = true;
            }
        }
        if ("sample".equalsIgnoreCase(type) || "all".equalsIgnoreCase(type)) {
            if (traceService.isSampling()) {
                traceService.stopSampling();
                stoppedSampling = true;
            }
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("stoppedTrace", stoppedTrace);
        response.put("stoppedSampling", stoppedSampling);
        response.put("message", buildStopMessage(stoppedTrace, stoppedSampling));

        return HttpResponseHelper.json(response);
    }

    private static String buildStopMessage(boolean trace, boolean sampling) {
        if (trace && sampling) {
            return "Stopped both tracing and sampling";
        } else if (trace) {
            return "Stopped method tracing";
        } else if (sampling) {
            return "Stopped stack sampling";
        }
        return "No active tracing or sampling to stop";
    }

    private static int getIntField(Map<?, ?> map, String key, int defaultValue) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }
}
