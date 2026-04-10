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

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for backing up and restoring class bytecode during hot-swap operations.
 *
 * <p>Before a hot-swap is applied, the original bytecode is saved in memory so it can be
 * restored (rolled back) later. The backup is stored in a {@link ConcurrentHashMap} keyed
 * by the fully qualified class name.
 *
 * <p>Thread-safe: all operations use concurrent data structures.
 */
public class BytecodeBackupService {

    private static final Logger LOG = Logger.getLogger(BytecodeBackupService.class.getName());

    /**
     * Map of className → original bytecode (before hot-swap).
     */
    private final ConcurrentHashMap<String, byte[]> backupStore = new ConcurrentHashMap<>();

    /**
     * Backs up the original bytecode of a class by reading it from the ClassLoader.
     *
     * <p>If the class has already been backed up, this method does NOT overwrite the
     * original backup — this ensures the very first (clean) version is preserved for
     * rollback.
     *
     * @param clazz the class to back up
     * @return {@code true} if a new backup was created, {@code false} if already backed up
     * @throws HotSwapException if the bytecode cannot be read
     */
    public boolean backup(Class<?> clazz) {
        String className = clazz.getName();

        // Don't overwrite existing backup — preserve the original
        if (backupStore.containsKey(className)) {
            LOG.fine("Bytecode already backed up for " + className);
            return false;
        }

        byte[] bytecode = loadBytecode(clazz);
        backupStore.putIfAbsent(className, bytecode);
        LOG.info("Backed up original bytecode for " + className + " (" + bytecode.length + " bytes)");
        return true;
    }

    /**
     * Backs up bytecode directly (when we already have the bytes).
     *
     * @param className the fully qualified class name
     * @param bytecode  the bytecode to back up
     * @return {@code true} if a new backup was created, {@code false} if already backed up
     */
    public boolean backup(String className, byte[] bytecode) {
        if (backupStore.containsKey(className)) {
            LOG.fine("Bytecode already backed up for " + className);
            return false;
        }

        backupStore.putIfAbsent(className, bytecode.clone());
        LOG.info("Backed up bytecode for " + className + " (" + bytecode.length + " bytes)");
        return true;
    }

    /**
     * Returns the backed-up bytecode for a class.
     *
     * @param className the fully qualified class name
     * @return the bytecode, or empty if no backup exists
     */
    public Optional<byte[]> getBackup(String className) {
        byte[] bytecode = backupStore.get(className);
        return Optional.ofNullable(bytecode != null ? bytecode.clone() : null);
    }

    /**
     * Checks whether a backup exists for the given class.
     *
     * @param className the fully qualified class name
     * @return {@code true} if a backup exists
     */
    public boolean hasBackup(String className) {
        return backupStore.containsKey(className);
    }

    /**
     * Removes the backup for a class (e.g., after a successful rollback).
     *
     * @param className the fully qualified class name
     * @return {@code true} if a backup was removed
     */
    public boolean removeBackup(String className) {
        return backupStore.remove(className) != null;
    }

    /**
     * Returns the set of all class names that have backups.
     *
     * @return unmodifiable set of class names
     */
    public Set<String> getBackedUpClasses() {
        return Collections.unmodifiableSet(backupStore.keySet());
    }

    /**
     * Returns the number of classes currently backed up.
     *
     * @return the backup count
     */
    public int size() {
        return backupStore.size();
    }

    /**
     * Clears all backups.
     */
    public void clear() {
        backupStore.clear();
        LOG.info("All bytecode backups cleared");
    }

    /**
     * Loads the bytecode of a class from its ClassLoader.
     */
    private byte[] loadBytecode(Class<?> clazz) {
        String resourceName = "/" + clazz.getName().replace('.', '/') + ".class";

        try (InputStream is = clazz.getResourceAsStream(resourceName)) {
            if (is != null) {
                return is.readAllBytes();
            }
        } catch (IOException e) {
            throw new HotSwapException("Failed to read bytecode for " + clazz.getName(), e);
        }

        // Try alternative path
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
