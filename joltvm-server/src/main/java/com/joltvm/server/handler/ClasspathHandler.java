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
import com.joltvm.server.jvm.JvmInfoService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.Map;

/**
 * Handler for {@code GET /api/jvm/classpath} — returns runtime classpath entries.
 */
public class ClasspathHandler implements RouteHandler {

    private final JvmInfoService jvmInfoService;

    public ClasspathHandler(JvmInfoService jvmInfoService) {
        this.jvmInfoService = jvmInfoService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        return HttpResponseHelper.json(jvmInfoService.getClasspath());
    }
}
