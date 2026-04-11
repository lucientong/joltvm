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

package com.joltvm.server.spring;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub implementation of {@link SpringContextService} for unit testing.
 *
 * <p>Allows test code to inject fake bean data, request mappings, and
 * dependency chains without requiring a real Spring ApplicationContext
 * or Instrumentation.
 */
public class StubSpringContextService extends SpringContextService {

    private boolean springDetected;
    private List<Map<String, Object>> beans = new ArrayList<>();
    private List<Map<String, Object>> mappings = new ArrayList<>();
    private final Map<String, Map<String, Object>> beanDetails = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> dependencyChains = new LinkedHashMap<>();
    private List<Map<String, Object>> dependencyGraph = new ArrayList<>();

    /**
     * Creates a stub with Spring detected = false and empty data.
     */
    public StubSpringContextService() {
        this(false);
    }

    /**
     * Creates a stub with the specified Spring detection state.
     *
     * @param springDetected whether Spring should be reported as detected
     */
    public StubSpringContextService(boolean springDetected) {
        this.springDetected = springDetected;
    }

    @Override
    public boolean isSpringDetected() {
        return springDetected;
    }

    @Override
    public List<Map<String, Object>> listBeans() {
        return new ArrayList<>(beans);
    }

    @Override
    public Map<String, Object> getBeanDetail(String beanName) {
        return beanDetails.get(beanName);
    }

    @Override
    public List<Map<String, Object>> getRequestMappings() {
        return new ArrayList<>(mappings);
    }

    @Override
    public Map<String, Object> getDependencyChain(String beanName) {
        return dependencyChains.get(beanName);
    }

    @Override
    public List<Map<String, Object>> getDependencyGraph() {
        return new ArrayList<>(dependencyGraph);
    }

    // ── Builder methods for test setup ────────────────────────────────

    public StubSpringContextService setSpringDetected(boolean detected) {
        this.springDetected = detected;
        return this;
    }

    /**
     * Adds a bean to the stub bean list.
     */
    public StubSpringContextService addBean(String name, String type, String simpleName,
                                             String pkg, String scope, List<String> stereotypes,
                                             boolean singleton) {
        Map<String, Object> bean = new LinkedHashMap<>();
        bean.put("name", name);
        bean.put("type", type);
        bean.put("simpleName", simpleName);
        bean.put("package", pkg);
        bean.put("scope", scope);
        bean.put("stereotypes", stereotypes);
        bean.put("singleton", singleton);
        beans.add(bean);
        return this;
    }

    /**
     * Adds a bean detail entry.
     */
    public StubSpringContextService addBeanDetail(String beanName, Map<String, Object> detail) {
        beanDetails.put(beanName, detail);
        return this;
    }

    /**
     * Adds a request mapping to the stub mappings list.
     */
    public StubSpringContextService addMapping(String url, String httpMethod, String beanName,
                                                String className, String method, String returnType,
                                                List<String> parameterTypes) {
        Map<String, Object> mapping = new LinkedHashMap<>();
        mapping.put("url", url);
        mapping.put("httpMethod", httpMethod);
        mapping.put("beanName", beanName);
        mapping.put("className", className);
        mapping.put("method", method);
        mapping.put("returnType", returnType);
        mapping.put("parameterTypes", parameterTypes);
        mappings.add(mapping);
        return this;
    }

    /**
     * Adds a dependency chain for a specific bean.
     */
    public StubSpringContextService addDependencyChain(String beanName, Map<String, Object> chain) {
        dependencyChains.put(beanName, chain);
        return this;
    }

    /**
     * Sets the dependency graph entries.
     */
    public StubSpringContextService setDependencyGraph(List<Map<String, Object>> graph) {
        this.dependencyGraph = new ArrayList<>(graph);
        return this;
    }
}
