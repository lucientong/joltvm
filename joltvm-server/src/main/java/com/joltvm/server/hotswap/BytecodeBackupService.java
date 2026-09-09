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

package com.joltvm.server.hotswap;

import com.joltvm.server.classloader.ClassLoaderService;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Service for backing up and restoring class bytecode during hot-swap operations.
 *
 * <p>Before a hot-swap is applied, the original bytecode is saved in memory so it can be
 * restored (rolled back) later. Keys prefer {@code loaderId::className} (via
 * {@link #backupKey(Class)}) and still accept legacy plain class-name keys for compatibility.
 *
 * <p>Thread-safe: all operations use concurrent data structures.
 */
public class BytecodeBackupService {

    private static final Logger LOG = Logger.getLogger(BytecodeBackupService.class.getName());

    /** Separator between ClassLoader id and class name in backup keys. */
    public static final String KEY_SEPARATOR = "::";

    /**
     * Maximum number of classes whose bytecode can be backed up simultaneously.
     *
     * <p>Prevents unbounded memory growth when many classes are hot-swapped without
     * being rolled back. Once the limit is reached, new hot-swap operations are rejected
     * with a {@link HotSwapException} until existing backups are released via rollback.
     */
    public static final int MAX_BACKUPS = 100;

    /**
     * Map of backupKey → original bytecode (before hot-swap).
     */
    private final ConcurrentHashMap<String, byte[]> backupStore = new ConcurrentHashMap<>();

    /**
     * Builds a backup key: {@code loaderId::className}.
     */
    public static String backupKey(Class<?> clazz) {
        return backupKey(ClassLoaderService.getLoaderId(clazz.getClassLoader()), clazz.getName());
    }

    /**
     * Builds a backup key: {@code loaderId::className}.
     */
    public static String backupKey(String loaderId, String className) {
        String id = (loaderId == null || loaderId.isBlank()) ? "bootstrap" : loaderId;
        return id + KEY_SEPARATOR + className;
    }

    /**
     * Backs up the original bytecode of a class by reading it from the ClassLoader.
     *
     * <p>If the class has already been backed up, this method does NOT overwrite the
     * original backup — this ensures the very first (clean) version is preserved for
     * rollback.
     *
     * @param clazz the class to back up
     * @return {@code true} if a new backup was created, {@code false} if already backed up
     * @throws HotSwapException if the bytecode cannot be read or the backup limit is reached
     */
    public boolean backup(Class<?> clazz) {
        String key = backupKey(clazz);

        if (backupStore.containsKey(key) || hasLegacyBackup(clazz.getName())) {
            LOG.fine("Bytecode already backed up for " + key);
            return false;
        }

        if (backupStore.size() >= MAX_BACKUPS) {
            throw new HotSwapException(
                    "Backup limit reached (" + MAX_BACKUPS + " classes). Roll back existing "
                            + "hot-swaps before applying new ones to free backup slots.");
        }

        byte[] bytecode = loadBytecode(clazz);
        backupStore.putIfAbsent(key, bytecode);
        LOG.info("Backed up original bytecode for " + key + " (" + bytecode.length + " bytes)");
        return true;
    }

    /**
     * Backs up bytecode directly (when we already have the bytes).
     *
     * <p>Uses the plain class name as key (legacy). Prefer {@link #backup(Class)} when
     * a loaded class is available so the key includes the ClassLoader id.
     *
     * @param className the fully qualified class name
     * @param bytecode  the bytecode to back up
     * @return {@code true} if a new backup was created, {@code false} if already backed up
     * @throws HotSwapException if the backup limit is reached
     */
    public boolean backup(String className, byte[] bytecode) {
        if (hasBackup(className)) {
            LOG.fine("Bytecode already backed up for " + className);
            return false;
        }

        if (backupStore.size() >= MAX_BACKUPS) {
            throw new HotSwapException(
                    "Backup limit reached (" + MAX_BACKUPS + " classes). Roll back existing "
                            + "hot-swaps before applying new ones to free backup slots.");
        }

        backupStore.putIfAbsent(className, bytecode.clone());
        LOG.info("Backed up bytecode for " + className + " (" + bytecode.length + " bytes)");
        return true;
    }

    /**
     * Backs up bytecode under an explicit {@code loaderId::className} key.
     */
    public boolean backup(String loaderId, String className, byte[] bytecode) {
        String key = backupKey(loaderId, className);
        if (backupStore.containsKey(key)) {
            LOG.fine("Bytecode already backed up for " + key);
            return false;
        }

        if (backupStore.size() >= MAX_BACKUPS) {
            throw new HotSwapException(
                    "Backup limit reached (" + MAX_BACKUPS + " classes). Roll back existing "
                            + "hot-swaps before applying new ones to free backup slots.");
        }

        backupStore.putIfAbsent(key, bytecode.clone());
        LOG.info("Backed up bytecode for " + key + " (" + bytecode.length + " bytes)");
        return true;
    }

    /**
     * Returns the backed-up bytecode for a class.
     *
     * <p>Accepts either a plain class name (legacy / unique match) or a full
     * {@code loaderId::className} key.
     *
     * @param classNameOrKey the fully qualified class name or backup key
     * @return the bytecode, or empty if no backup exists
     */
    public Optional<byte[]> getBackup(String classNameOrKey) {
        byte[] bytecode = resolveBackup(classNameOrKey);
        return Optional.ofNullable(bytecode != null ? bytecode.clone() : null);
    }

    /**
     * Returns backup for a specific ClassLoader + class name.
     */
    public Optional<byte[]> getBackup(String loaderId, String className) {
        return getBackup(backupKey(loaderId, className));
    }

    /**
     * Checks whether a backup exists for the given class or key.
     */
    public boolean hasBackup(String classNameOrKey) {
        return resolveBackup(classNameOrKey) != null;
    }

    /**
     * Removes the backup for a class (e.g., after a successful rollback).
     *
     * @param classNameOrKey the fully qualified class name or backup key
     * @return {@code true} if a backup was removed
     */
    public boolean removeBackup(String classNameOrKey) {
        if (backupStore.remove(classNameOrKey) != null) {
            return true;
        }
        // Remove legacy plain key or unique loader-qualified key
        if (classNameOrKey != null && !classNameOrKey.contains(KEY_SEPARATOR)) {
            if (backupStore.remove(classNameOrKey) != null) {
                return true;
            }
            String suffix = KEY_SEPARATOR + classNameOrKey;
            String matchedKey = null;
            for (String key : backupStore.keySet()) {
                if (key.endsWith(suffix)) {
                    if (matchedKey != null) {
                        return false; // ambiguous — require full key
                    }
                    matchedKey = key;
                }
            }
            if (matchedKey != null) {
                return backupStore.remove(matchedKey) != null;
            }
        }
        return false;
    }

    /**
     * Returns the set of all backup keys (may be {@code loaderId::className} or plain names).
     */
    public Set<String> getBackedUpClasses() {
        return Collections.unmodifiableSet(backupStore.keySet());
    }

    public int size() {
        return backupStore.size();
    }

    public void clear() {
        backupStore.clear();
        LOG.info("All bytecode backups cleared");
    }

    private boolean hasLegacyBackup(String className) {
        return backupStore.containsKey(className);
    }

    private byte[] resolveBackup(String classNameOrKey) {
        if (classNameOrKey == null) {
            return null;
        }
        byte[] direct = backupStore.get(classNameOrKey);
        if (direct != null) {
            return direct;
        }
        if (classNameOrKey.contains(KEY_SEPARATOR)) {
            return null;
        }
        // Legacy plain name: also accept unique loaderId::className match
        String suffix = KEY_SEPARATOR + classNameOrKey;
        byte[] found = null;
        for (Map.Entry<String, byte[]> entry : backupStore.entrySet()) {
            if (entry.getKey().endsWith(suffix)) {
                if (found != null) {
                    return null; // ambiguous
                }
                found = entry.getValue();
            }
        }
        return found;
    }

    private byte[] loadBytecode(Class<?> clazz) {
        String resourceName = "/" + clazz.getName().replace('.', '/') + ".class";

        try (InputStream is = clazz.getResourceAsStream(resourceName)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (IOException e) {
            throw new HotSwapException("Failed to read bytecode for " + clazz.getName(), e);
        }

        String altName = clazz.getName().replace('.', '/') + ".class";
        ClassLoader cl = clazz.getClassLoader();
        if (cl != null) {
            try (InputStream is = cl.getResourceAsStream(altName)) {
                if (is != null) {
                    return is.readAllBytes();
                }
            } catch (IOException e) {
                throw new HotSwapException("Failed to read bytecode for " + clazz.getName(), e);
            }
        }

        throw new HotSwapException(
                "Cannot read bytecode for " + clazz.getName()
                        + ". The class may be generated dynamically or loaded by a restricted ClassLoader.");
    }
}
