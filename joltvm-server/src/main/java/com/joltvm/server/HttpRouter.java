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

import io.netty.handler.codec.http.HttpMethod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simple HTTP router that maps method + path patterns to {@link RouteHandler} instances.
 *
 * <p>Supports path parameters using {@code {paramName}} syntax. For example:
 * <pre>
 *   router.addRoute(HttpMethod.GET, "/api/classes/{className}/source", handler);
 * </pre>
 * The matched parameter can be retrieved from the path params map passed to the handler.
 *
 * <p>Routes are matched in registration order. The first match wins.
 */
public final class HttpRouter {

    private final List<Route> routes = new ArrayList<>();

    /**
     * Registers a route.
     *
     * @param method  the HTTP method
     * @param pattern the URL path pattern (e.g., "/api/classes/{className}/source")
     * @param handler the handler to invoke when the route matches
     * @throws NullPointerException if any argument is null
     */
    public void addRoute(HttpMethod method, String pattern, RouteHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(pattern, "pattern");
        Objects.requireNonNull(handler, "handler");
        routes.add(new Route(method, pattern, handler));
    }

    /**
     * Finds a matching route for the given method and URI path.
     *
     * @param method the HTTP method
     * @param path   the request URI path
     * @return a {@link RouteMatch} if a route matches, or {@code null} if no route matches
     */
    public RouteMatch match(HttpMethod method, String path) {
        for (Route route : routes) {
            if (!route.method.equals(method)) {
                continue;
            }
            Map<String, String> params = route.matchPath(path);
            if (params != null) {
                return new RouteMatch(route.handler, params);
            }
        }
        return null;
    }

    /**
     * Returns an unmodifiable view of the registered routes (for debugging/testing).
     *
     * @return list of registered routes
     */
    public List<Route> getRoutes() {
        return Collections.unmodifiableList(routes);
    }

    // ========================================================================
    // Inner classes
    // ========================================================================

    /**
     * Represents a registered route.
     */
    public static final class Route {
        private final HttpMethod method;
        private final String pattern;
        private final RouteHandler handler;
        private final Pattern compiledPattern;
        private final List<String> paramNames;

        Route(HttpMethod method, String pattern, RouteHandler handler) {
            this.method = method;
            this.pattern = pattern;
            this.handler = handler;
            this.paramNames = new ArrayList<>();
            this.compiledPattern = compilePattern(pattern);
        }

        /**
         * Returns the route pattern string.
         *
         * @return the pattern
         */
        public String getPattern() {
            return pattern;
        }

        /**
         * Returns the HTTP method.
         *
         * @return the method
         */
        public HttpMethod getMethod() {
            return method;
        }

        /**
         * Tries to match the given path against this route.
         *
         * @param path the request path
         * @return a map of parameter names to values, or {@code null} if no match
         */
        Map<String, String> matchPath(String path) {
            Matcher matcher = compiledPattern.matcher(path);
            if (!matcher.matches()) {
                return null;
            }
            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < paramNames.size(); i++) {
                params.put(paramNames.get(i), matcher.group(i + 1));
            }
            return params;
        }

        private Pattern compilePattern(String pattern) {
            // Convert {paramName} to named regex groups
            StringBuilder regex = new StringBuilder("^");
            Matcher paramMatcher = Pattern.compile("\\{(\\w+)}").matcher(pattern);
            int lastEnd = 0;
            while (paramMatcher.find()) {
                regex.append(Pattern.quote(pattern.substring(lastEnd, paramMatcher.start())));
                regex.append("([^/]+)");
                paramNames.add(paramMatcher.group(1));
                lastEnd = paramMatcher.end();
            }
            regex.append(Pattern.quote(pattern.substring(lastEnd)));
            regex.append("$");
            return Pattern.compile(regex.toString());
        }
    }

    /**
     * Result of a successful route match.
     *
     * @param handler    the matched handler
     * @param pathParams the extracted path parameters
     */
    public record RouteMatch(RouteHandler handler, Map<String, String> pathParams) {
    }
}
