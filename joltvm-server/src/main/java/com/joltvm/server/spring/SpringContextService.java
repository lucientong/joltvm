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

import com.joltvm.agent.InstrumentationHolder;

import java.lang.annotation.Annotation;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for discovering and inspecting Spring Boot application context via reflection.
 *
 * <p>This service does <b>not</b> have any compile-time dependency on Spring Framework.
 * All Spring classes and annotations are accessed purely via reflection, making it safe
 * to use in non-Spring JVMs (where it simply reports "Spring not detected").
 *
 * <p>Capabilities:
 * <ul>
 *   <li>Discover {@code ApplicationContext} from loaded classes</li>
 *   <li>List all registered Spring beans with metadata</li>
 *   <li>Parse {@code @RequestMapping} (and shortcuts like {@code @GetMapping}) to
 *       build a URL → method mapping table</li>
 *   <li>Detect bean stereotypes: {@code @Controller}, {@code @Service}, {@code @Repository},
 *       {@code @Component}, {@code @Configuration}</li>
 * </ul>
 *
 * <p>Compatible with Spring Boot 2.x and 3.x.
 *
 * @see com.joltvm.server.handler.BeanListHandler
 * @see com.joltvm.server.handler.BeanDetailHandler
 * @see com.joltvm.server.handler.RequestMappingHandler
 */
public class SpringContextService {

    private static final Logger LOG = Logger.getLogger(SpringContextService.class.getName());

    /** Well-known Spring stereotype annotations (fully qualified names). */
    private static final Set<String> STEREOTYPE_ANNOTATIONS = Set.of(
            "org.springframework.stereotype.Controller",
            "org.springframework.stereotype.Service",
            "org.springframework.stereotype.Repository",
            "org.springframework.stereotype.Component",
            "org.springframework.context.annotation.Configuration",
            "org.springframework.web.bind.annotation.RestController"
    );

    /** Request mapping annotation names to HTTP method mapping. */
    private static final Map<String, String> MAPPING_ANNOTATION_TO_METHOD = Map.of(
            "org.springframework.web.bind.annotation.RequestMapping", "",
            "org.springframework.web.bind.annotation.GetMapping", "GET",
            "org.springframework.web.bind.annotation.PostMapping", "POST",
            "org.springframework.web.bind.annotation.PutMapping", "PUT",
            "org.springframework.web.bind.annotation.DeleteMapping", "DELETE",
            "org.springframework.web.bind.annotation.PatchMapping", "PATCH"
    );

    /** Cached ApplicationContext instances discovered from the JVM. */
    private final CopyOnWriteArrayList<Object> cachedContexts = new CopyOnWriteArrayList<>();

    private volatile boolean springDetected = false;
    private volatile boolean scanned = false;

    /**
     * Checks whether Spring Framework is detected in the target JVM.
     *
     * @return true if Spring ApplicationContext class is loaded
     */
    public boolean isSpringDetected() {
        if (!scanned) {
            refresh();
        }
        return springDetected;
    }

    /**
     * Rescans the loaded classes to discover Spring ApplicationContext instances.
     *
     * <p>This method iterates all loaded classes (via Instrumentation) looking for
     * instances of {@code SpringApplication} or {@code ApplicationContext} holders.
     */
    public void refresh() {
        cachedContexts.clear();
        springDetected = false;
        scanned = true;

        if (!InstrumentationHolder.isAvailable()) {
            LOG.fine("Instrumentation not available, cannot scan for Spring context");
            return;
        }

        Instrumentation inst = InstrumentationHolder.get();
        Class<?>[] allClasses = inst.getAllLoadedClasses();

        // Strategy 1: Look for SpringApplication.context static field (Spring Boot)
        // Strategy 2: Look for WebApplicationContext in ServletContext
        // Strategy 3: Look for ApplicationContext implementations directly

        Class<?> appCtxClass = findClass(allClasses, "org.springframework.context.ApplicationContext");
        if (appCtxClass == null) {
            LOG.fine("Spring ApplicationContext class not found among loaded classes");
            return;
        }

        springDetected = true;
        LOG.info("Spring Framework detected in target JVM");

        // Try to find active ApplicationContext via LiveBeansView or SpringApplication
        tryDiscoverContextFromSpringApplication(allClasses, appCtxClass);

        if (cachedContexts.isEmpty()) {
            tryDiscoverContextFromLiveBeansView(allClasses, appCtxClass);
        }

        if (cachedContexts.isEmpty()) {
            tryDiscoverContextByScanning(allClasses, appCtxClass);
        }

        LOG.info("Discovered " + cachedContexts.size() + " ApplicationContext instance(s)");
    }

    /**
     * Returns all discovered ApplicationContext instances.
     *
     * @return unmodifiable list of ApplicationContext objects
     */
    public List<Object> getContexts() {
        if (!scanned) {
            refresh();
        }
        return Collections.unmodifiableList(new ArrayList<>(cachedContexts));
    }

    /**
     * Lists all beans from all discovered ApplicationContexts.
     *
     * @return list of bean info maps, each containing name, type, scope, stereotypes, etc.
     */
    public List<Map<String, Object>> listBeans() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object ctx : getContexts()) {
            try {
                String[] beanNames = invokeBeanNames(ctx);
                if (beanNames == null) {
                    continue;
                }
                for (String beanName : beanNames) {
                    Map<String, Object> beanInfo = buildBeanInfo(ctx, beanName);
                    if (beanInfo != null) {
                        result.add(beanInfo);
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error listing beans from context", e);
            }
        }

        result.sort(Comparator.comparing(m -> (String) m.getOrDefault("name", "")));
        return result;
    }

    /**
     * Returns detailed information about a specific bean.
     *
     * @param beanName the bean name
     * @return bean detail map, or null if not found
     */
    public Map<String, Object> getBeanDetail(String beanName) {
        for (Object ctx : getContexts()) {
            try {
                Method containsBean = ctx.getClass().getMethod("containsBean", String.class);
                boolean contains = (boolean) containsBean.invoke(ctx, beanName);
                if (!contains) {
                    continue;
                }
                return buildBeanDetail(ctx, beanName);
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error getting bean detail: " + beanName, e);
            }
        }
        return null;
    }

    /**
     * Builds a list of URL → method mappings from all {@code @RequestMapping} annotations.
     *
     * @return list of mapping info maps
     */
    public List<Map<String, Object>> getRequestMappings() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object ctx : getContexts()) {
            try {
                String[] beanNames = invokeBeanNames(ctx);
                if (beanNames == null) {
                    continue;
                }
                for (String beanName : beanNames) {
                    Class<?> beanType = getBeanType(ctx, beanName);
                    if (beanType == null) {
                        continue;
                    }
                    // Only scan classes with controller-related annotations
                    if (!hasControllerAnnotation(beanType)) {
                        continue;
                    }
                    List<Map<String, Object>> mappings = extractMappings(beanName, beanType);
                    result.addAll(mappings);
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error extracting request mappings", e);
            }
        }

        result.sort(Comparator.comparing(m -> (String) m.getOrDefault("url", "")));
        return result;
    }

    /**
     * Analyzes the dependency chain for a given bean, showing its injected dependencies
     * and their stereotypes (e.g., {@code @Controller → @Service → @Repository}).
     *
     * <p>Inspects fields and constructor parameters annotated with {@code @Autowired},
     * {@code @Inject}, or {@code @Resource} to discover direct dependencies.
     * Then recursively builds the dependency tree.
     *
     * @param beanName the root bean name
     * @return a dependency chain map, or null if bean not found
     */
    public Map<String, Object> getDependencyChain(String beanName) {
        for (Object ctx : getContexts()) {
            try {
                Method containsBean = ctx.getClass().getMethod("containsBean", String.class);
                if (!(boolean) containsBean.invoke(ctx, beanName)) {
                    continue;
                }
                return buildDependencyChain(ctx, beanName, new java.util.HashSet<>());
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error building dependency chain for: " + beanName, e);
            }
        }
        return null;
    }

    /**
     * Returns the full dependency graph for all beans, showing
     * {@code @Controller → @Service → @Repository} relationships.
     *
     * @return list of dependency chain entries
     */
    public List<Map<String, Object>> getDependencyGraph() {
        List<Map<String, Object>> result = new ArrayList<>();

        for (Object ctx : getContexts()) {
            try {
                String[] beanNames = invokeBeanNames(ctx);
                if (beanNames == null) {
                    continue;
                }
                for (String beanName : beanNames) {
                    Class<?> beanType = getBeanType(ctx, beanName);
                    if (beanType == null) {
                        continue;
                    }
                    // Only include beans with stereotypes (user-defined components)
                    List<String> stereotypes = detectStereotypes(beanType);
                    if (stereotypes.isEmpty()) {
                        continue;
                    }
                    // Detect injected dependencies
                    List<Map<String, Object>> deps = detectInjectedDependencies(ctx, beanType);
                    if (!deps.isEmpty() || hasControllerAnnotation(beanType)) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("beanName", beanName);
                        entry.put("className", beanType.getName());
                        entry.put("stereotypes", stereotypes);
                        entry.put("dependencies", deps);
                        result.add(entry);
                    }
                }
            } catch (Exception e) {
                LOG.log(Level.FINE, "Error building dependency graph", e);
            }
        }

        result.sort(Comparator.comparing(m -> (String) m.getOrDefault("beanName", "")));
        return result;
    }

    private Map<String, Object> buildDependencyChain(Object ctx, String beanName,
                                                      java.util.Set<String> visited) {
        if (visited.contains(beanName)) {
            // Circular dependency — stop recursion
            Map<String, Object> circular = new LinkedHashMap<>();
            circular.put("beanName", beanName);
            circular.put("circular", true);
            return circular;
        }
        visited.add(beanName);

        Class<?> beanType = getBeanType(ctx, beanName);
        if (beanType == null) {
            return null;
        }

        Map<String, Object> node = new LinkedHashMap<>();
        node.put("beanName", beanName);
        node.put("className", beanType.getName());
        node.put("stereotypes", detectStereotypes(beanType));

        List<Map<String, Object>> deps = detectInjectedDependencies(ctx, beanType);
        List<Map<String, Object>> resolvedDeps = new ArrayList<>();
        for (Map<String, Object> dep : deps) {
            String depBeanName = (String) dep.get("beanName");
            if (depBeanName != null) {
                Map<String, Object> childChain = buildDependencyChain(ctx, depBeanName, visited);
                if (childChain != null) {
                    resolvedDeps.add(childChain);
                } else {
                    resolvedDeps.add(dep);
                }
            } else {
                resolvedDeps.add(dep);
            }
        }
        node.put("dependencies", resolvedDeps);

        visited.remove(beanName);
        return node;
    }

    private List<Map<String, Object>> detectInjectedDependencies(Object ctx, Class<?> beanType) {
        List<Map<String, Object>> deps = new ArrayList<>();

        // Scan fields for @Autowired, @Inject, @Resource
        try {
            for (java.lang.reflect.Field field : beanType.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (hasInjectionAnnotation(field)) {
                    Map<String, Object> dep = new LinkedHashMap<>();
                    dep.put("fieldName", field.getName());
                    dep.put("fieldType", field.getType().getName());
                    dep.put("injectionType", "field");

                    // Try to resolve the bean name for this type
                    String resolvedBeanName = resolveBeanName(ctx, field.getName(), field.getType());
                    if (resolvedBeanName != null) {
                        dep.put("beanName", resolvedBeanName);
                        Class<?> depType = getBeanType(ctx, resolvedBeanName);
                        if (depType != null) {
                            dep.put("resolvedType", depType.getName());
                            dep.put("stereotypes", detectStereotypes(depType));
                        }
                    }
                    deps.add(dep);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error scanning fields for: " + beanType.getName(), e);
        }

        // Scan constructors for @Autowired
        try {
            for (java.lang.reflect.Constructor<?> ctor : beanType.getDeclaredConstructors()) {
                if (hasInjectionAnnotation(ctor) || beanType.getDeclaredConstructors().length == 1) {
                    Class<?>[] paramTypes = ctor.getParameterTypes();
                    java.lang.reflect.Parameter[] params = ctor.getParameters();
                    for (int i = 0; i < paramTypes.length; i++) {
                        Map<String, Object> dep = new LinkedHashMap<>();
                        dep.put("parameterName", params[i].getName());
                        dep.put("fieldType", paramTypes[i].getName());
                        dep.put("injectionType", "constructor");

                        String resolvedBeanName = resolveBeanName(ctx, params[i].getName(), paramTypes[i]);
                        if (resolvedBeanName != null) {
                            dep.put("beanName", resolvedBeanName);
                            Class<?> depType = getBeanType(ctx, resolvedBeanName);
                            if (depType != null) {
                                dep.put("resolvedType", depType.getName());
                                dep.put("stereotypes", detectStereotypes(depType));
                            }
                        }
                        deps.add(dep);
                    }
                    break; // Only scan one constructor
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error scanning constructors for: " + beanType.getName(), e);
        }

        return deps;
    }

    private boolean hasInjectionAnnotation(java.lang.reflect.AnnotatedElement element) {
        for (Annotation ann : element.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (name.equals("org.springframework.beans.factory.annotation.Autowired")
                    || name.equals("jakarta.inject.Inject")
                    || name.equals("javax.inject.Inject")
                    || name.equals("jakarta.annotation.Resource")
                    || name.equals("javax.annotation.Resource")) {
                return true;
            }
        }
        return false;
    }

    private String resolveBeanName(Object ctx, String fieldName, Class<?> type) {
        // Strategy 1: Try field name as bean name
        try {
            Method containsBean = ctx.getClass().getMethod("containsBean", String.class);
            if ((boolean) containsBean.invoke(ctx, fieldName)) {
                return fieldName;
            }
        } catch (Exception e) {
            // ignore
        }

        // Strategy 2: Try getting bean names for the type
        try {
            Method getBeanNamesForType = ctx.getClass().getMethod("getBeanNamesForType", Class.class);
            String[] names = (String[]) getBeanNamesForType.invoke(ctx, type);
            if (names != null && names.length > 0) {
                return names[0];
            }
        } catch (Exception e) {
            // ignore
        }

        return null;
    }

    /**
     * Resets the service state. Intended for testing only.
     */
    public void reset() {
        cachedContexts.clear();
        springDetected = false;
        scanned = false;
    }

    // ── Context discovery strategies ──────────────────────────────────

    private void tryDiscoverContextFromSpringApplication(Class<?>[] allClasses, Class<?> appCtxClass) {
        // Look for org.springframework.boot.SpringApplication or similar
        // that holds a static reference to the running ApplicationContext
        for (Class<?> clazz : allClasses) {
            if ("org.springframework.boot.SpringApplication".equals(clazz.getName())) {
                // Spring Boot doesn't keep a static ref, but we can try the context holder
                break;
            }
        }

        // Try ContextLoader (Spring MVC) — holds current WebApplicationContext
        for (Class<?> clazz : allClasses) {
            if ("org.springframework.web.context.ContextLoader".equals(clazz.getName())) {
                try {
                    Method getCurrentCtx = clazz.getMethod("getCurrentWebApplicationContext");
                    Object ctx = getCurrentCtx.invoke(null);
                    if (ctx != null && appCtxClass.isInstance(ctx)) {
                        cachedContexts.add(ctx);
                        LOG.info("Found ApplicationContext via ContextLoader");
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "ContextLoader approach failed", e);
                }
                break;
            }
        }
    }

    private void tryDiscoverContextFromLiveBeansView(Class<?>[] allClasses, Class<?> appCtxClass) {
        // LiveBeansView keeps a static Set<ConfigurableApplicationContext>
        for (Class<?> clazz : allClasses) {
            if ("org.springframework.context.support.LiveBeansView".equals(clazz.getName())) {
                try {
                    java.lang.reflect.Field field = clazz.getDeclaredField("applicationContexts");
                    field.setAccessible(true);
                    Object set = field.get(null);
                    if (set instanceof Set<?> ctxSet) {
                        for (Object ctx : ctxSet) {
                            if (appCtxClass.isInstance(ctx)) {
                                cachedContexts.add(ctx);
                            }
                        }
                        if (!cachedContexts.isEmpty()) {
                            LOG.info("Found " + cachedContexts.size()
                                    + " ApplicationContext(s) via LiveBeansView");
                        }
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "LiveBeansView approach failed", e);
                }
                break;
            }
        }
    }

    private void tryDiscoverContextByScanning(Class<?>[] allClasses, Class<?> appCtxClass) {
        // Last resort: scan all loaded classes for concrete ApplicationContext implementations
        // that have a getBeanDefinitionNames() method and try to get singleton instances
        for (Class<?> clazz : allClasses) {
            if (appCtxClass.isAssignableFrom(clazz) && !clazz.isInterface()
                    && !Modifier.isAbstract(clazz.getModifiers())) {
                // Found a concrete ApplicationContext class — but we need an instance
                // Try static fields or instance tracking (best-effort)
                try {
                    for (java.lang.reflect.Field f : clazz.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())
                                && appCtxClass.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object ctx = f.get(null);
                            if (ctx != null && appCtxClass.isInstance(ctx)) {
                                cachedContexts.add(ctx);
                                LOG.info("Found ApplicationContext via static field scan: "
                                        + clazz.getName() + "." + f.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "Static field scan failed for: " + clazz.getName(), e);
                }
            }
        }
    }

    // ── Bean introspection ────────────────────────────────────────────

    private String[] invokeBeanNames(Object ctx) {
        try {
            Method method = ctx.getClass().getMethod("getBeanDefinitionNames");
            return (String[]) method.invoke(ctx);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Cannot invoke getBeanDefinitionNames", e);
            return null;
        }
    }

    private Class<?> getBeanType(Object ctx, String beanName) {
        try {
            Method getType = ctx.getClass().getMethod("getType", String.class);
            return (Class<?>) getType.invoke(ctx, beanName);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Cannot get type for bean: " + beanName, e);
            return null;
        }
    }

    private Map<String, Object> buildBeanInfo(Object ctx, String beanName) {
        try {
            Class<?> beanType = getBeanType(ctx, beanName);
            if (beanType == null) {
                return null;
            }

            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", beanName);
            info.put("type", beanType.getName());
            info.put("simpleName", beanType.getSimpleName());

            Package pkg = beanType.getPackage();
            info.put("package", pkg != null ? pkg.getName() : "");

            // Detect scope
            info.put("scope", detectScope(ctx, beanName));

            // Detect stereotypes
            info.put("stereotypes", detectStereotypes(beanType));

            // Check if it's a singleton
            try {
                Method isSingleton = ctx.getClass().getMethod("isSingleton", String.class);
                info.put("singleton", isSingleton.invoke(ctx, beanName));
            } catch (Exception e) {
                info.put("singleton", null);
            }

            return info;
        } catch (Exception e) {
            LOG.log(Level.FINE, "Error building bean info for: " + beanName, e);
            return null;
        }
    }

    private Map<String, Object> buildBeanDetail(Object ctx, String beanName) {
        Map<String, Object> info = buildBeanInfo(ctx, beanName);
        if (info == null) {
            return null;
        }

        Class<?> beanType = getBeanType(ctx, beanName);
        if (beanType == null) {
            return info;
        }

        // Add superclass
        Class<?> superclass = beanType.getSuperclass();
        info.put("superclass", superclass != null ? superclass.getName() : null);

        // Add interfaces
        info.put("interfaces", Arrays.stream(beanType.getInterfaces())
                .map(Class::getName)
                .toList());

        // Add all annotations
        info.put("annotations", getAnnotationNames(beanType));

        // Add declared methods
        List<Map<String, Object>> methods = new ArrayList<>();
        try {
            for (Method m : beanType.getDeclaredMethods()) {
                if (m.isSynthetic()) {
                    continue;
                }
                Map<String, Object> methodInfo = new LinkedHashMap<>();
                methodInfo.put("name", m.getName());
                methodInfo.put("returnType", m.getReturnType().getName());
                methodInfo.put("parameterTypes", Arrays.stream(m.getParameterTypes())
                        .map(Class::getName)
                        .toList());
                methodInfo.put("modifiers", Modifier.toString(m.getModifiers()));
                methodInfo.put("annotations", getAnnotationNames(m));
                methods.add(methodInfo);
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Cannot read methods for bean: " + beanName, e);
        }
        info.put("methods", methods);

        // Add request mappings if this is a controller
        if (hasControllerAnnotation(beanType)) {
            info.put("requestMappings", extractMappings(beanName, beanType));
        }

        return info;
    }

    private String detectScope(Object ctx, String beanName) {
        // Try to get BeanDefinition from BeanFactory
        try {
            // ctx.getBeanFactory().getBeanDefinition(beanName).getScope()
            Method getBeanFactory = findMethod(ctx.getClass(), "getBeanFactory");
            if (getBeanFactory != null) {
                Object factory = getBeanFactory.invoke(ctx);
                if (factory != null) {
                    Method getBeanDef = findMethod(factory.getClass(), "getBeanDefinition", String.class);
                    if (getBeanDef != null) {
                        Object beanDef = getBeanDef.invoke(factory, beanName);
                        if (beanDef != null) {
                            Method getScope = findMethod(beanDef.getClass(), "getScope");
                            if (getScope != null) {
                                Object scope = getScope.invoke(beanDef);
                                if (scope instanceof String s && !s.isEmpty()) {
                                    return s;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Cannot detect scope for bean: " + beanName, e);
        }
        return "singleton"; // default in Spring
    }

    // ── Request mapping extraction ────────────────────────────────────

    private boolean hasControllerAnnotation(Class<?> clazz) {
        for (Annotation ann : clazz.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (name.equals("org.springframework.stereotype.Controller")
                    || name.equals("org.springframework.web.bind.annotation.RestController")) {
                return true;
            }
            // Also check meta-annotations (e.g. @RestController is meta-annotated with @Controller)
            for (Annotation metaAnn : ann.annotationType().getAnnotations()) {
                String metaName = metaAnn.annotationType().getName();
                if (metaName.equals("org.springframework.stereotype.Controller")) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<Map<String, Object>> extractMappings(String beanName, Class<?> beanType) {
        List<Map<String, Object>> mappings = new ArrayList<>();

        // Get class-level @RequestMapping paths
        List<String> classPaths = getRequestMappingPaths(beanType.getAnnotations());
        if (classPaths.isEmpty()) {
            classPaths = List.of("");
        }

        // Scan methods
        for (Method method : beanType.getDeclaredMethods()) {
            if (method.isSynthetic() || Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            for (Annotation ann : method.getAnnotations()) {
                String annName = ann.annotationType().getName();
                String httpMethod = MAPPING_ANNOTATION_TO_METHOD.get(annName);
                if (httpMethod == null) {
                    continue;
                }

                // Get paths from method-level mapping
                List<String> methodPaths = getMappingPaths(ann);
                if (methodPaths.isEmpty()) {
                    methodPaths = List.of("");
                }

                // If it's @RequestMapping, resolve HTTP method from annotation
                List<String> httpMethods;
                if (httpMethod.isEmpty()) {
                    httpMethods = resolveRequestMethods(ann);
                    if (httpMethods.isEmpty()) {
                        httpMethods = List.of("GET", "POST", "PUT", "DELETE", "PATCH");
                    }
                } else {
                    httpMethods = List.of(httpMethod);
                }

                // Combine class-level and method-level paths
                for (String classPath : classPaths) {
                    for (String methodPath : methodPaths) {
                        String url = normalizePath(classPath, methodPath);
                        for (String hm : httpMethods) {
                            Map<String, Object> mapping = new LinkedHashMap<>();
                            mapping.put("url", url);
                            mapping.put("httpMethod", hm);
                            mapping.put("beanName", beanName);
                            mapping.put("className", beanType.getName());
                            mapping.put("method", method.getName());
                            mapping.put("returnType", method.getReturnType().getName());
                            mapping.put("parameterTypes", Arrays.stream(method.getParameterTypes())
                                    .map(Class::getName)
                                    .toList());
                            mappings.add(mapping);
                        }
                    }
                }
            }
        }
        return mappings;
    }

    private List<String> getRequestMappingPaths(Annotation[] annotations) {
        for (Annotation ann : annotations) {
            String annName = ann.annotationType().getName();
            if ("org.springframework.web.bind.annotation.RequestMapping".equals(annName)) {
                return getMappingPaths(ann);
            }
        }
        return List.of();
    }

    private List<String> getMappingPaths(Annotation ann) {
        // Try value() first, then path()
        try {
            Method valueMethod = ann.annotationType().getMethod("value");
            String[] values = (String[]) valueMethod.invoke(ann);
            if (values != null && values.length > 0) {
                return Arrays.asList(values);
            }
        } catch (Exception e) {
            // ignore
        }
        try {
            Method pathMethod = ann.annotationType().getMethod("path");
            String[] paths = (String[]) pathMethod.invoke(ann);
            if (paths != null && paths.length > 0) {
                return Arrays.asList(paths);
            }
        } catch (Exception e) {
            // ignore
        }
        return List.of();
    }

    private List<String> resolveRequestMethods(Annotation ann) {
        try {
            Method methodAttr = ann.annotationType().getMethod("method");
            Object[] methods = (Object[]) methodAttr.invoke(ann);
            if (methods != null && methods.length > 0) {
                List<String> result = new ArrayList<>();
                for (Object m : methods) {
                    result.add(m.toString());
                }
                return result;
            }
        } catch (Exception e) {
            // ignore
        }
        return List.of();
    }

    // ── Utility methods ──────────────────────────────────────────────

    static String normalizePath(String classPath, String methodPath) {
        String path = classPath + "/" + methodPath;
        // Collapse multiple slashes
        path = path.replaceAll("/+", "/");
        // Ensure starts with /
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        // Remove trailing slash (except for root)
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private List<String> detectStereotypes(Class<?> beanType) {
        List<String> stereotypes = new ArrayList<>();
        for (Annotation ann : beanType.getAnnotations()) {
            String name = ann.annotationType().getName();
            if (STEREOTYPE_ANNOTATIONS.contains(name)) {
                stereotypes.add(ann.annotationType().getSimpleName());
            }
            // Check meta-annotations (e.g., @RestController → @Controller)
            for (Annotation metaAnn : ann.annotationType().getAnnotations()) {
                String metaName = metaAnn.annotationType().getName();
                if (STEREOTYPE_ANNOTATIONS.contains(metaName)
                        && !stereotypes.contains(metaAnn.annotationType().getSimpleName())) {
                    stereotypes.add(metaAnn.annotationType().getSimpleName());
                }
            }
        }
        return stereotypes;
    }

    private List<String> getAnnotationNames(java.lang.reflect.AnnotatedElement element) {
        try {
            return Arrays.stream(element.getAnnotations())
                    .map(a -> a.annotationType().getName())
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static Class<?> findClass(Class<?>[] allClasses, String fqcn) {
        for (Class<?> clazz : allClasses) {
            if (fqcn.equals(clazz.getName())) {
                return clazz;
            }
        }
        return null;
    }

    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        try {
            return clazz.getMethod(name, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
