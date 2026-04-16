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
import com.joltvm.server.watch.WatchService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;

/**
 * Handler for {@code POST /api/watch/{id}/stop} — stops a watch session.
 */
public class WatchStopHandler implements RouteHandler {

    private final WatchService watchService;

    public WatchStopHandler(WatchService watchService) {
        this.watchService = watchService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        String id = pathParams.get("id");
        if (id == null || id.isEmpty()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST, "Missing watch session id");
        }

        Map<String, Object> result = watchService.stopWatch(id);
        if (result == null) {
            return HttpResponseHelper.error(HttpResponseStatus.NOT_FOUND, "Watch session not found: " + id);
        }
        return HttpResponseHelper.json(result);
    }
}
