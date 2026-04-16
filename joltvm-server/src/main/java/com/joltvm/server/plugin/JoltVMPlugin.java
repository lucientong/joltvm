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

import java.util.List;

/**
 * SPI interface for JoltVM plugins.
 *
 * <p>Plugins are discovered via {@link java.util.ServiceLoader} from the
 * {@code plugins/} directory. Each plugin runs in its own isolated ClassLoader.
 *
 * <p>Lifecycle: {@code onLoad} → {@code onStart} → (running) → {@code onStop}
 */
public interface JoltVMPlugin {

    /** Unique plugin identifier (e.g., "my-plugin"). Must be URL-safe. */
    String id();

    /** Human-readable plugin name. */
    String name();

    /** Plugin version (e.g., "1.0.0"). */
    String version();

    /**
     * Called when the plugin is loaded. Use this to initialize resources.
     *
     * @param context the plugin context providing access to JoltVM services
     */
    void onLoad(PluginContext context);

    /** Called when the plugin is started (after all plugins are loaded). */
    void onStart();

    /** Called when the plugin is stopped (during JoltVM shutdown). */
    void onStop();

    /**
     * Returns REST route definitions contributed by this plugin.
     * Routes are automatically prefixed with {@code /api/plugins/{id}/}.
     *
     * @return list of route definitions, or empty list
     */
    default List<RouteDefinition> routes() {
        return List.of();
    }

    /**
     * Returns paths to web asset files (JS/CSS) to be loaded by the Web UI.
     *
     * @return list of asset paths relative to the plugin JAR, or empty list
     */
    default List<String> webAssetPaths() {
        return List.of();
    }
}
