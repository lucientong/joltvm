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

package com.joltvm.server;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.Map;

/**
 * Functional interface for handling an HTTP request and producing a response.
 *
 * <p>Implementations receive the full request, path parameters extracted by the
 * {@link HttpRouter}, and return a complete HTTP response.
 *
 * @see HttpRouter
 * @see HttpResponseHelper
 */
@FunctionalInterface
public interface RouteHandler {

    /**
     * Handles an HTTP request.
     *
     * @param request    the full HTTP request
     * @param pathParams path parameters extracted from the URL (e.g., {@code {className}})
     * @return a full HTTP response
     */
    FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams);
}
