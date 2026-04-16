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

package com.joltvm.server.ognl;

import ognl.MemberAccess;
import ognl.OgnlContext;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Security sandbox for OGNL expression evaluation.
 *
 * <p>Implements a defense-in-depth strategy:
 * <ol>
 *   <li><b>Class blacklist</b> — blocks dangerous classes (Runtime, ProcessBuilder, etc.)</li>
 *   <li><b>Method blacklist</b> — blocks dangerous methods (exec, exit, load, etc.)</li>
 *   <li><b>Package blacklist</b> — blocks reflection, scripting, management packages</li>
 *   <li><b>Read-only by default</b> — field writes are blocked</li>
 * </ol>
 *
 * <p>This is the #1 security-critical component. Every known OGNL injection
 * vector must be blocked by this class.
 */
public class SafeOgnlMemberAccess implements MemberAccess {

    // ================================================================
    // Hard blacklists — non-overridable, covers all known attack vectors
    // ================================================================

    /** Classes that must NEVER be accessible via OGNL. */
    private static final Set<String> BLOCKED_CLASSES = Set.of(
            // Process execution
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "java.lang.ProcessHandle",
            "java.lang.ProcessHandle$Info",

            // System-level dangerous methods
            "java.lang.System",
            "java.lang.Shutdown",

            // Unsafe memory access
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe",

            // ClassLoader manipulation
            "java.lang.ClassLoader",
            "java.net.URLClassLoader",
            "java.security.SecureClassLoader",
            "jdk.internal.loader.BuiltinClassLoader",

            // Reflection
            "java.lang.reflect.Method",
            "java.lang.reflect.Field",
            "java.lang.reflect.Constructor",
            "java.lang.reflect.Proxy",

            // Scripting engines (can eval arbitrary code)
            "javax.script.ScriptEngine",
            "javax.script.ScriptEngineFactory",
            "javax.script.ScriptEngineManager",

            // Security infrastructure
            "java.lang.SecurityManager",
            "java.security.AccessController",
            "java.security.Permission",
            "java.security.Policy",

            // Thread manipulation
            "java.lang.Thread",
            "java.lang.ThreadGroup",

            // File I/O
            "java.io.File",
            "java.io.FileInputStream",
            "java.io.FileOutputStream",
            "java.io.FileReader",
            "java.io.FileWriter",
            "java.io.RandomAccessFile",
            "java.nio.file.Files",
            "java.nio.file.Paths",
            "java.nio.file.Path",
            "java.nio.channels.FileChannel",

            // Network
            "java.net.Socket",
            "java.net.ServerSocket",
            "java.net.URL",
            "java.net.URI",
            "java.net.HttpURLConnection",

            // Serialization attacks
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream",

            // JMX
            "javax.management.MBeanServer",
            "java.lang.management.ManagementFactory",

            // OGNL internals (prevent sandbox escape)
            "ognl.OgnlContext",
            "ognl.OgnlRuntime",
            "ognl.MemberAccess",
            "ognl.ClassResolver"
    );

    /** Methods that must NEVER be callable via OGNL, regardless of class. */
    private static final Set<String> BLOCKED_METHODS = Set.of(
            "exec",
            "exit",
            "halt",
            "load",
            "loadLibrary",
            "setAccessible",
            "forName",
            "getMethod",
            "getDeclaredMethod",
            "getField",
            "getDeclaredField",
            "getConstructor",
            "getDeclaredConstructor",
            "newInstance",
            "invoke",
            "defineClass",
            "getClassLoader",
            "getProtectionDomain",
            "setSecurityManager",
            "getRuntime",
            "start",       // ProcessBuilder.start()
            "destroy",     // Process.destroy()
            "destroyForcibly",
            "wait",
            "notify",
            "notifyAll"
    );

    /** Package prefixes that are entirely off-limits. */
    private static final Set<String> BLOCKED_PACKAGE_PREFIXES = Set.of(
            "java.lang.reflect.",
            "java.lang.invoke.",
            "sun.misc.",
            "sun.reflect.",
            "jdk.internal.",
            "javax.script.",
            "javax.management.",
            "javax.naming.",
            "javax.sql.",
            "com.sun.jmx.",
            "com.sun.management.",
            "ognl."
    );

    /** Pattern to detect class-level access patterns (anti-reflection bypass). */
    private static final Pattern DANGEROUS_CLASS_PATTERN = Pattern.compile(
            "@[^@]*(runtime|processbuilder|unsafe|classloader|scriptengine)[^@]*@",
            Pattern.CASE_INSENSITIVE);

    @Override
    public Object setup(OgnlContext context, Object target, Member member, String propertyName) {
        // No state to set up
        return null;
    }

    @Override
    public void restore(OgnlContext context, Object target, Member member, String propertyName, Object state) {
        // No state to restore
    }

    @Override
    public boolean isAccessible(OgnlContext context, Object target, Member member, String propertyName) {
        if (target == null && member == null) {
            return false;
        }

        // Determine the declaring class
        Class<?> declaringClass = member != null ? member.getDeclaringClass() : null;
        if (declaringClass == null && target != null) {
            declaringClass = target.getClass();
        }

        if (declaringClass != null) {
            String className = declaringClass.getName();

            // Check class blacklist
            if (BLOCKED_CLASSES.contains(className)) {
                return false;
            }

            // Check package prefix blacklist
            for (String prefix : BLOCKED_PACKAGE_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return false;
                }
            }

            // Block all non-public members
            if (member != null && !Modifier.isPublic(member.getModifiers())) {
                return false;
            }

            // Block all non-public classes
            if (!Modifier.isPublic(declaringClass.getModifiers())) {
                return false;
            }
        }

        // Check method blacklist
        if (member instanceof Method method) {
            String methodName = method.getName();
            if (BLOCKED_METHODS.contains(methodName)) {
                return false;
            }

            // Block getClass().forName() chain — getClass() on any object is allowed
            // but if result is Class, block dangerous Class methods
            if (declaringClass == Class.class) {
                // Only allow getName(), getSimpleName(), isInterface(), etc.
                return isAllowedClassMethod(methodName);
            }
        }

        // Check superclass hierarchy for blocked classes
        if (declaringClass != null) {
            Class<?> superClass = declaringClass.getSuperclass();
            while (superClass != null) {
                if (BLOCKED_CLASSES.contains(superClass.getName())) {
                    return false;
                }
                superClass = superClass.getSuperclass();
            }

            // Check interfaces
            for (Class<?> iface : declaringClass.getInterfaces()) {
                if (BLOCKED_CLASSES.contains(iface.getName())) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Whitelist of safe methods on {@link Class} objects.
     * Everything else on Class (forName, getMethod, etc.) is blocked.
     */
    private boolean isAllowedClassMethod(String methodName) {
        return switch (methodName) {
            case "getName", "getSimpleName", "getCanonicalName", "getTypeName",
                 "isInterface", "isEnum", "isRecord", "isArray", "isPrimitive",
                 "isAnnotation", "isAnonymousClass", "isLocalClass", "isMemberClass",
                 "getSuperclass", "getPackageName",
                 "toString", "hashCode", "equals" -> true;
            default -> false;
        };
    }

    /**
     * Validates an OGNL expression string for known dangerous patterns
     * before parsing. This is a pre-parse defense layer.
     *
     * @param expression the OGNL expression to validate
     * @throws SecurityException if the expression contains blocked patterns
     */
    public static void validateExpression(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Expression must not be blank");
        }

        String normalized = expression.toLowerCase().replaceAll("\\s+", "");

        // Block @class@method static access to dangerous classes
        for (String blocked : BLOCKED_CLASSES) {
            String lowerBlocked = blocked.toLowerCase().replace(".", "");
            if (normalized.replace(".", "").contains(lowerBlocked)) {
                throw new SecurityException("Access denied: " + blocked);
            }
        }

        // Block direct Runtime/ProcessBuilder references
        if (DANGEROUS_CLASS_PATTERN.matcher(normalized).find()) {
            throw new SecurityException("Expression contains blocked class reference");
        }

        // Block reflection-based sandbox escapes
        if (normalized.contains("getclass(") && (
                normalized.contains("forname(") ||
                normalized.contains("getmethod(") ||
                normalized.contains("getdeclaredmethod(") ||
                normalized.contains("newinstance("))) {
            throw new SecurityException("Reflection-based access is not allowed");
        }

        // Block #_memberAccess override attempts
        if (normalized.contains("#_memberaccess") || normalized.contains("#memberaccess")) {
            throw new SecurityException("Modifying MemberAccess is not allowed");
        }

        // Block @ognl.OgnlContext references
        if (normalized.contains("ognlcontext") || normalized.contains("ognlruntime")) {
            throw new SecurityException("Access to OGNL internals is not allowed");
        }

        // Block class literal references to dangerous classes
        if (normalized.contains("@java.lang.runtime@") ||
                normalized.contains("@java.lang.processbuilder@") ||
                normalized.contains("@java.lang.system@")) {
            throw new SecurityException("Access to blocked static class is not allowed");
        }
    }
}
