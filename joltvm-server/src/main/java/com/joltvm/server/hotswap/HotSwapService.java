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

import com.joltvm.agent.InstrumentationHolder;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for performing hot-swap (class redefinition) and rollback operations.
 *
 * <p>This service integrates with {@link BytecodeBackupService} to preserve original
 * bytecode before applying changes, and maintains an audit trail of all operations
 * via {@link HotSwapRecord}.
 *
 * <h3>Hot-Swap Flow:</h3>
 * <ol>
 *   <li>Validate: check that the target class exists and redefine is supported</li>
 *   <li>Backup: save the original bytecode (first time only)</li>
 *   <li>Redefine: apply new bytecode via {@code Instrumentation.redefineClasses()}</li>
 *   <li>Record: log the operation to history</li>
 * </ol>
 *
 * <h3>Rollback Flow:</h3>
 * <ol>
 *   <li>Retrieve backed-up original bytecode</li>
 *   <li>Redefine: re-apply original bytecode</li>
 *   <li>Remove backup entry</li>
 *   <li>Record: log the rollback to history</li>
 * </ol>
 *
 * <p>Thread-safe: uses {@link LinkedBlockingDeque} for bounded history and per-class
 * {@link ReentrantLock} to serialize concurrent hot-swap / rollback on the same class.
 */
public class HotSwapService {

    private static final Logger LOG = Logger.getLogger(HotSwapService.class.getName());
    private static final int MAX_HISTORY = 200;

    private final BytecodeBackupService backupService;
    private final LinkedBlockingDeque<HotSwapRecord> history = new LinkedBlockingDeque<>(MAX_HISTORY);

    /**
     * Per-class locks that serialize concurrent hotSwap / rollback on the same class name.
     * This prevents {@code Instrumentation.redefineClasses} calls from racing with each other.
     */
    private final ConcurrentHashMap<String, ReentrantLock> classLocks = new ConcurrentHashMap<>();

    public HotSwapService() {
        this.backupService = new BytecodeBackupService();
    }

    // Visible for testing
    public HotSwapService(BytecodeBackupService backupService) {
        this.backupService = backupService;
    }

    /**
     * Performs a hot-swap: redefines a loaded class with new bytecode.
     *
     * @param className   the fully qualified class name
     * @param newBytecode the new bytecode to apply
     * @return the hot-swap record
     * @throws HotSwapException if the operation fails
     */
    public HotSwapRecord hotSwap(String className, byte[] newBytecode) {
        return hotSwap(className, newBytecode, null, null);
    }

    /**
     * Performs a hot-swap with operator tracking.
     *
     * @param className   the fully qualified class name
     * @param newBytecode the new bytecode to apply
     * @param operator    the user performing the operation (may be null)
     * @param reason      the reason for the hot-swap (may be null)
     * @return the hot-swap record
     */
    public HotSwapRecord hotSwap(String className, byte[] newBytecode,
                                  String operator, String reason) {
        return hotSwap(className, newBytecode, operator, reason, null);
    }

    /**
     * Performs a hot-swap with operator tracking and a pre-computed diff.
     *
     * @param className        the fully qualified class name
     * @param newBytecode      the new bytecode to apply
     * @param operator         the user performing the operation (may be null)
     * @param reason           the reason for the hot-swap (may be null)
     * @param precomputedDiff  a unified diff string computed by the caller (may be null)
     * @return the hot-swap record
     */
    public HotSwapRecord hotSwap(String className, byte[] newBytecode,
                                  String operator, String reason, String precomputedDiff) {
        ReentrantLock lock = classLocks.computeIfAbsent(className, k -> new ReentrantLock());
        lock.lock();
        try {
            return doHotSwap(className, newBytecode, operator, reason, precomputedDiff);
        } finally {
            lock.unlock();
        }
    }

    private HotSwapRecord doHotSwap(String className, byte[] newBytecode,
                                     String operator, String reason, String precomputedDiff) {
        Instrumentation inst = InstrumentationHolder.get();

        // 1. Validate redefine support
        if (!inst.isRedefineClassesSupported()) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class redefinition is not supported by this JVM");
            addHistory(record);
            return record;
        }

        // 2. Find the target class
        Class<?> targetClass = findLoadedClass(className, inst);
        if (targetClass == null) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class not found among loaded classes: " + className);
            addHistory(record);
            return record;
        }

        // 3. Check if class is modifiable
        if (!inst.isModifiableClass(targetClass)) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class is not modifiable: " + className);
            addHistory(record);
            return record;
        }

        // 4. Backup original bytecode (first time only)
        try {
            backupService.backup(targetClass);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to backup bytecode for " + className, e);
            // Continue with hot-swap even if backup fails — warn the user
        }

        // 5. Apply the hot-swap
        try {
            ClassDefinition definition = new ClassDefinition(targetClass, newBytecode);
            inst.redefineClasses(definition);

            // Use pre-computed diff if available, otherwise fall back to byte summary
            String diff = precomputedDiff;
            if (diff == null) {
                diff = "Bytecode replaced: " + newBytecode.length + " bytes";
                Optional<byte[]> originalBackup = backupService.getBackup(className);
                if (originalBackup.isPresent()) {
                    diff = "Original: " + originalBackup.get().length + " bytes → New: "
                            + newBytecode.length + " bytes";
                }
            }

            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.SUCCESS,
                    "Successfully redefined " + className + " (" + newBytecode.length + " bytes)",
                    operator, reason, diff);
            addHistory(record);
            LOG.info("Hot-swap successful: " + className);
            return record;

        } catch (UnsupportedOperationException e) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED,
                    "Structural change not supported: " + e.getMessage());
            addHistory(record);
            return record;
        } catch (ClassFormatError e) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED,
                    "Invalid class format: " + e.getMessage());
            addHistory(record);
            return record;
        } catch (UnmodifiableClassException e) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED,
                    "Class cannot be modified: " + e.getMessage());
            addHistory(record);
            return record;
        } catch (Exception e) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED,
                    "Hot-swap failed: " + e.getMessage());
            addHistory(record);
            return record;
        }
    }

    /**
     * Rolls back a previously hot-swapped class to its original bytecode.
     *
     * @param className the fully qualified class name
     * @return the rollback record
     * @throws HotSwapException if the operation fails
     */
    public HotSwapRecord rollback(String className) {
        return rollback(className, null, null);
    }

    /**
     * Rolls back with operator tracking.
     *
     * <p>Serialized per class name to prevent races with concurrent hotSwap calls.
     *
     * @param className the fully qualified class name
     * @param operator  the user performing the rollback (may be null)
     * @param reason    the reason for rollback (may be null)
     * @return the rollback record
     */
    public HotSwapRecord rollback(String className, String operator, String reason) {
        ReentrantLock lock = classLocks.computeIfAbsent(className, k -> new ReentrantLock());
        lock.lock();
        try {
            return doRollback(className, operator, reason);
        } finally {
            lock.unlock();
        }
    }

    private HotSwapRecord doRollback(String className, String operator, String reason) {
        Instrumentation inst = InstrumentationHolder.get();

        // 1. Check backup exists
        Optional<byte[]> backup = backupService.getBackup(className);
        if (backup.isEmpty()) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.ROLLBACK,
                    HotSwapRecord.Status.FAILED,
                    "No backup found for class: " + className + ". Cannot rollback.");
            addHistory(record);
            return record;
        }

        // 2. Find the target class
        Class<?> targetClass = findLoadedClass(className, inst);
        if (targetClass == null) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.ROLLBACK,
                    HotSwapRecord.Status.FAILED,
                    "Class not found among loaded classes: " + className);
            addHistory(record);
            return record;
        }

        // 3. Apply the rollback
        byte[] originalBytecode = backup.get();
        try {
            ClassDefinition definition = new ClassDefinition(targetClass, originalBytecode);
            inst.redefineClasses(definition);

            // Remove backup after successful rollback
            backupService.removeBackup(className);

            String diff = "Restored original bytecode: " + originalBytecode.length + " bytes";
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.ROLLBACK,
                    HotSwapRecord.Status.SUCCESS,
                    "Successfully rolled back " + className + " to original bytecode",
                    operator, reason, diff);
            addHistory(record);
            LOG.info("Rollback successful: " + className);
            return record;

        } catch (Exception e) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.ROLLBACK,
                    HotSwapRecord.Status.FAILED,
                    "Rollback failed: " + e.getMessage());
            addHistory(record);
            return record;
        }
    }

    /**
     * Returns the list of classes that have been hot-swapped and can be rolled back.
     *
     * @return unmodifiable set of class names with backups
     */
    public java.util.Set<String> getRollbackableClasses() {
        return backupService.getBackedUpClasses();
    }

    /**
     * Returns the operation history, newest first.
     *
     * @return unmodifiable list of hot-swap records
     */
    public List<HotSwapRecord> getHistory() {
        List<HotSwapRecord> snapshot = new ArrayList<>(history);
        Collections.reverse(snapshot);
        return Collections.unmodifiableList(snapshot);
    }

    /**
     * Returns the operation history, newest first, limited to the specified count.
     *
     * @param limit maximum number of records to return
     * @return unmodifiable list of hot-swap records
     */
    public List<HotSwapRecord> getHistory(int limit) {
        List<HotSwapRecord> all = getHistory();
        if (limit >= all.size()) {
            return all;
        }
        return all.subList(0, limit);
    }

    /**
     * Returns the backup service for direct access (e.g., checking backup status).
     *
     * @return the backup service
     */
    public BytecodeBackupService getBackupService() {
        return backupService;
    }

    private Class<?> findLoadedClass(String className, Instrumentation inst) {
        for (Class<?> clazz : inst.getAllLoadedClasses()) {
            if (clazz.getName().equals(className)) {
                return clazz;
            }
        }
        return null;
    }

    private HotSwapRecord createRecord(String className, HotSwapRecord.Action action,
                                        HotSwapRecord.Status status, String message) {
        return createRecord(className, action, status, message, null, null, null);
    }

    private HotSwapRecord createRecord(String className, HotSwapRecord.Action action,
                                        HotSwapRecord.Status status, String message,
                                        String operator, String reason, String diff) {
        return new HotSwapRecord(
                UUID.randomUUID().toString().substring(0, 8),
                className,
                action,
                status,
                message,
                Instant.now(),
                operator,
                reason,
                diff
        );
    }

    private void addHistory(HotSwapRecord record) {
        // If deque is full, remove the oldest entry to make room
        while (!history.offerLast(record)) {
            history.pollFirst();
        }
    }
}
