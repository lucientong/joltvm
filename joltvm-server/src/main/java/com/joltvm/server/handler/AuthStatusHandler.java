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
import com.joltvm.server.security.SecurityConfig;
import com.joltvm.server.security.TokenService;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler for {@code GET /api/auth/status} — returns current auth status.
 *
 * <p>Returns whether authentication is enabled and, if a valid token
 * is provided, the current user's info.
 */
public class AuthStatusHandler implements RouteHandler {

    private final SecurityConfig securityConfig;
    private final TokenService tokenService;

    public AuthStatusHandler(SecurityConfig securityConfig, TokenService tokenService) {
        this.securityConfig = securityConfig;
        this.tokenService = tokenService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authEnabled", securityConfig.isEnabled());

        if (!securityConfig.isEnabled()) {
            response.put("role", "ADMIN");
            response.put("username", "anonymous");
            return HttpResponseHelper.json(response);
        }

        // Try to extract token
        String authHeader = request.headers().get("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            TokenService.TokenInfo info = tokenService.validateToken(token);
            if (info != null) {
                response.put("authenticated", true);
                response.put("username", info.username());
                response.put("role", info.role().name());
                response.put("expires", info.expiration().toString());
                return HttpResponseHelper.json(response);
            }
        }

        response.put("authenticated", false);
        return HttpResponseHelper.json(response);
    }
}
