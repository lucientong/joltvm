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
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler for {@code GET /api/health} — health check endpoint.
 *
 * <p>Returns server status, uptime, and JVM information. Useful for
 * verifying that the JoltVM server is running inside the target JVM.
 */
public final class HealthHandler implements RouteHandler {

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();

        Map<String, Object> health = new LinkedHashMap<>();
        health.put("status", "UP");
        health.put("name", "JoltVM");
        health.put("version", getVersion());
        health.put("jvm", Map.of(
                "name", runtime.getVmName(),
                "vendor", runtime.getVmVendor(),
                "version", runtime.getVmVersion(),
                "pid", ProcessHandle.current().pid(),
                "uptimeMs", runtime.getUptime(),
                "startTime", runtime.getStartTime()
        ));
        health.put("memory", Map.of(
                "totalMb", Runtime.getRuntime().totalMemory() / (1024 * 1024),
                "freeMb", Runtime.getRuntime().freeMemory() / (1024 * 1024),
                "maxMb", Runtime.getRuntime().maxMemory() / (1024 * 1024)
        ));

        return HttpResponseHelper.json(health);
    }

    private static String getVersion() {
        String version = HealthHandler.class.getPackage().getImplementationVersion();
        return version != null ? version : "dev";
    }
}
