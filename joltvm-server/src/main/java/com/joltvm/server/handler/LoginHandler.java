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
import io.netty.handler.codec.http.HttpResponseStatus;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler for {@code POST /api/auth/login} — authenticates user and returns a token.
 *
 * <p>Expects a JSON request body:
 * <pre>
 * {
 *   "username": "admin",
 *   "password": "joltvm"
 * }
 * </pre>
 *
 * <p>Returns on success:
 * <pre>
 * {
 *   "token": "...",
 *   "username": "admin",
 *   "role": "ADMIN",
 *   "expiresIn": 86400
 * }
 * </pre>
 */
public class LoginHandler implements RouteHandler {

    private final SecurityConfig securityConfig;
    private final TokenService tokenService;

    public LoginHandler(SecurityConfig securityConfig, TokenService tokenService) {
        this.securityConfig = securityConfig;
        this.tokenService = tokenService;
    }

    @Override
    public FullHttpResponse handle(FullHttpRequest request, Map<String, String> pathParams) {
        // If security is disabled, return a dummy admin token
        if (!securityConfig.isEnabled()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("token", "disabled");
            response.put("username", "anonymous");
            response.put("role", "ADMIN");
            response.put("authEnabled", false);
            response.put("message", "Authentication is disabled");
            return HttpResponseHelper.json(response);
        }

        String body = request.content().toString(StandardCharsets.UTF_8);
        if (body.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Request body is required. Expected JSON with 'username' and 'password' fields.");
        }

        Map<?, ?> bodyMap;
        try {
            bodyMap = HttpResponseHelper.gson().fromJson(body, Map.class);
        } catch (Exception e) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Invalid JSON body: " + e.getMessage());
        }

        String username = (String) bodyMap.get("username");
        String password = (String) bodyMap.get("password");

        if (username == null || username.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'username' is required");
        }
        if (password == null || password.isBlank()) {
            return HttpResponseHelper.error(HttpResponseStatus.BAD_REQUEST,
                    "Field 'password' is required");
        }

        SecurityConfig.UserEntry user = securityConfig.authenticate(username, password);
        if (user == null) {
            return HttpResponseHelper.error(HttpResponseStatus.UNAUTHORIZED,
                    "Invalid username or password");
        }

        String token = tokenService.generateToken(user.username(), user.role());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("token", token);
        response.put("username", user.username());
        response.put("role", user.role().name());
        response.put("authEnabled", true);
        return HttpResponseHelper.json(response);
    }
}
