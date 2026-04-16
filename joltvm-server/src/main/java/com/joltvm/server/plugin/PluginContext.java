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

package com.joltvm.server.plugin;

import com.joltvm.server.HttpRouter;

import java.util.logging.Logger;

/**
 * Context object passed to plugins during {@link JoltVMPlugin#onLoad(PluginContext)}.
 *
 * <p>Provides access to JoltVM infrastructure services that plugins may need.
 */
public class PluginContext {

    private final HttpRouter router;
    private final Logger logger;
    private final String pluginId;

    public PluginContext(String pluginId, HttpRouter router) {
        this.pluginId = pluginId;
        this.router = router;
        this.logger = Logger.getLogger("joltvm.plugin." + pluginId);
    }

    /** Returns the HTTP router for registering custom routes. */
    public HttpRouter getRouter() {
        return router;
    }

    /** Returns a logger namespaced to this plugin. */
    public Logger getLogger() {
        return logger;
    }

    /** Returns the plugin's unique ID. */
    public String getPluginId() {
        return pluginId;
    }
}
