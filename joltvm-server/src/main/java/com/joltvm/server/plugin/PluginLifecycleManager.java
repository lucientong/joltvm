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

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages the lifecycle of JoltVM plugins.
 *
 * <p>Discovery: Scans the {@code plugins/} directory for JAR files, creates
 * an isolated {@link URLClassLoader} per plugin, and discovers implementations
 * via {@link ServiceLoader}.
 *
 * <p>Lifecycle phases:
 * <ol>
 *   <li><b>Discover</b> — Find plugin JARs and load via ServiceLoader</li>
 *   <li><b>Load</b> — Call {@link JoltVMPlugin#onLoad(PluginContext)} with context</li>
 *   <li><b>Start</b> — Call {@link JoltVMPlugin#onStart()} and register routes</li>
 *   <li><b>Stop</b> — Call {@link JoltVMPlugin#onStop()} during shutdown</li>
 * </ol>
 */
public class PluginLifecycleManager {

    private static final Logger LOG = Logger.getLogger(PluginLifecycleManager.class.getName());

    private static final String PLUGINS_DIR = "plugins";
    private static final String ROUTE_PREFIX = "/api/plugins/";

    private final HttpRouter router;
    private final List<PluginEntry> loadedPlugins = new ArrayList<>();
    private final List<URLClassLoader> pluginClassLoaders = new ArrayList<>();

    public PluginLifecycleManager(HttpRouter router) {
        this.router = router;
    }

    /**
     * Discovers and loads all plugins from the plugins directory.
     *
     * @return number of plugins loaded
     */
    public int discoverAndLoad() {
        File pluginsDir = new File(PLUGINS_DIR);
        if (!pluginsDir.isDirectory()) {
            LOG.fine("No plugins directory found at: " + pluginsDir.getAbsolutePath());
            return 0;
        }

        File[] jars = pluginsDir.listFiles((dir, name) -> name.endsWith(".jar"));
        if (jars == null || jars.length == 0) {
            LOG.fine("No plugin JARs found in: " + pluginsDir.getAbsolutePath());
            return 0;
        }

        int count = 0;
        for (File jar : jars) {
            try {
                count += loadPluginJar(jar);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to load plugin from: " + jar.getName(), e);
            }
        }

        // Start all loaded plugins
        for (PluginEntry entry : loadedPlugins) {
            try {
                entry.plugin.onStart();
                LOG.info("Plugin started: " + entry.plugin.id() + " v" + entry.plugin.version());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to start plugin: " + entry.plugin.id(), e);
            }
        }

        LOG.info("Loaded " + count + " plugin(s) from " + jars.length + " JAR(s)");
        return count;
    }

    private int loadPluginJar(File jar) throws Exception {
        URL jarUrl = jar.toURI().toURL();
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jarUrl},
                getClass().getClassLoader() // parent = JoltVM classloader
        );
        pluginClassLoaders.add(classLoader);

        ServiceLoader<JoltVMPlugin> loader = ServiceLoader.load(JoltVMPlugin.class, classLoader);
        int count = 0;

        for (JoltVMPlugin plugin : loader) {
            String id = plugin.id();
            if (id == null || id.isBlank() || !id.matches("[a-zA-Z0-9._-]+")) {
                LOG.warning("Plugin has invalid id: " + id + " from " + jar.getName());
                continue;
            }

            // Check for duplicate IDs
            if (loadedPlugins.stream().anyMatch(e -> e.plugin.id().equals(id))) {
                LOG.warning("Duplicate plugin id: " + id + " from " + jar.getName() + " — skipping");
                continue;
            }

            try {
                // Load phase
                PluginContext context = new PluginContext(id, router);
                plugin.onLoad(context);

                // Register routes
                List<RouteDefinition> routes = plugin.routes();
                if (routes != null) {
                    for (RouteDefinition route : routes) {
                        String fullPath = ROUTE_PREFIX + id + "/" + route.path().replaceFirst("^/", "");
                        router.addRoute(route.method(), fullPath, route.handler());
                        LOG.fine("Registered plugin route: " + route.method() + " " + fullPath);
                    }
                }

                loadedPlugins.add(new PluginEntry(plugin, classLoader, jar.getName()));
                count++;
                LOG.info("Plugin loaded: " + id + " (" + plugin.name() + " v" + plugin.version() + ")");
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Failed to load plugin: " + id, e);
            }
        }

        return count;
    }

    /**
     * Returns information about all loaded plugins.
     *
     * @return map with "plugins" list and "count"
     */
    public Map<String, Object> listPlugins() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (PluginEntry entry : loadedPlugins) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("id", entry.plugin.id());
            info.put("name", entry.plugin.name());
            info.put("version", entry.plugin.version());
            info.put("source", entry.source);
            info.put("routeCount", entry.plugin.routes() != null ? entry.plugin.routes().size() : 0);
            info.put("webAssetCount", entry.plugin.webAssetPaths() != null ? entry.plugin.webAssetPaths().size() : 0);
            list.add(info);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plugins", list);
        result.put("count", list.size());
        return result;
    }

    /**
     * Stops all plugins and closes their classloaders.
     */
    public void shutdown() {
        for (PluginEntry entry : loadedPlugins) {
            try {
                entry.plugin.onStop();
                LOG.info("Plugin stopped: " + entry.plugin.id());
            } catch (Exception e) {
                LOG.log(Level.WARNING, "Error stopping plugin: " + entry.plugin.id(), e);
            }
        }
        loadedPlugins.clear();

        for (URLClassLoader cl : pluginClassLoaders) {
            try {
                cl.close();
            } catch (Exception ignored) {
            }
        }
        pluginClassLoaders.clear();
    }

    /** Returns the number of loaded plugins. */
    public int getPluginCount() {
        return loadedPlugins.size();
    }

    private record PluginEntry(JoltVMPlugin plugin, URLClassLoader classLoader, String source) {
    }
}
