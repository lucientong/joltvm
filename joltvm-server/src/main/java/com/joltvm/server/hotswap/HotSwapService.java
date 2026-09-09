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
import com.joltvm.server.classloader.AmbiguousClassException;
import com.joltvm.server.classloader.ClassLoaderService;
import com.joltvm.server.classloader.LoadedClassResolver;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Supports ClassLoader-disambiguated resolve via {@code classLoaderId}, and
 * batch redefinition of compiled outer + inner classes in a single
 * {@link Instrumentation#redefineClasses} call.
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

    public HotSwapRecord hotSwap(String className, byte[] newBytecode) {
        return hotSwap(className, newBytecode, null, null, null, null);
    }

    public HotSwapRecord hotSwap(String className, byte[] newBytecode,
                                  String operator, String reason) {
        return hotSwap(className, newBytecode, operator, reason, null, null);
    }

    public HotSwapRecord hotSwap(String className, byte[] newBytecode,
                                  String operator, String reason, String precomputedDiff) {
        return hotSwap(className, newBytecode, operator, reason, precomputedDiff, null);
    }

    /**
     * Performs a hot-swap with optional ClassLoader disambiguation.
     *
     * @param className        fully qualified class name
     * @param newBytecode      new bytecode
     * @param operator         optional operator
     * @param reason           optional reason
     * @param precomputedDiff  optional unified diff
     * @param classLoaderId    optional ClassLoader id ({@link ClassLoaderService#getLoaderId})
     * @return the hot-swap record
     * @throws AmbiguousClassException if multiple loaders match and no id was given
     */
    public HotSwapRecord hotSwap(String className, byte[] newBytecode,
                                  String operator, String reason, String precomputedDiff,
                                  String classLoaderId) {
        ReentrantLock lock = classLocks.computeIfAbsent(className, k -> new ReentrantLock());
        lock.lock();
        try {
            return doHotSwap(className, newBytecode, operator, reason, precomputedDiff, classLoaderId);
        } finally {
            lock.unlock();
        }
    }

    private HotSwapRecord doHotSwap(String className, byte[] newBytecode,
                                     String operator, String reason, String precomputedDiff,
                                     String classLoaderId) {
        Instrumentation inst = InstrumentationHolder.get();

        if (!inst.isRedefineClassesSupported()) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class redefinition is not supported by this JVM");
            addHistory(record);
            return record;
        }

        Class<?> targetClass = LoadedClassResolver.resolve(className, classLoaderId);
        if (targetClass == null) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class not found among loaded classes: " + className
                            + (classLoaderId != null ? " (classLoaderId=" + classLoaderId + ")" : ""));
            addHistory(record);
            return record;
        }

        if (!inst.isModifiableClass(targetClass)) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class is not modifiable: " + className);
            addHistory(record);
            return record;
        }

        try {
            backupService.backup(targetClass);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to backup bytecode for " + className, e);
        }

        try {
            ClassDefinition definition = new ClassDefinition(targetClass, newBytecode);
            inst.redefineClasses(definition);

            String diff = precomputedDiff;
            if (diff == null) {
                diff = "Bytecode replaced: " + newBytecode.length + " bytes";
                Optional<byte[]> originalBackup = backupService.getBackup(
                        ClassLoaderService.getLoaderId(targetClass.getClassLoader()), className);
                if (originalBackup.isEmpty()) {
                    originalBackup = backupService.getBackup(className);
                }
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
                    "Structural change not supported: " + e.getMessage()
                            + ". JVM redefine cannot add/remove methods or fields.");
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
     * Result of a batch hot-swap spanning an outer class and its compiled companions
     * (named inner classes, anonymous classes, etc.).
     *
     * @param success     whether the atomic redefine succeeded (or nothing to redefine)
     * @param primaryRecord history record for the primary class (may be synthetic)
     * @param redefined   class names successfully redefined
     * @param skipped     skipped entries with reason
     * @param message     summary message
     */
    public record BatchHotSwapResult(
            boolean success,
            HotSwapRecord primaryRecord,
            List<String> redefined,
            List<Map<String, String>> skipped,
            String message
    ) {}

    /**
     * Backs up all eligible loaded classes from {@code bytecodeMap}, then applies a
     * single atomic {@code redefineClasses} call.
     *
     * <p>Classes that are not loaded, not modifiable, or otherwise ineligible are
     * listed under {@code skipped} with a reason (e.g. anonymous {@code $1} not yet loaded).
     *
     * @param bytecodeMap   compiled class name → bytecode (includes inner classes)
     * @param primaryClass  the primary class name from the API request
     * @param classLoaderId optional ClassLoader id for resolve
     * @param operator      optional operator
     * @param reason        optional reason
     * @param precomputedDiff optional diff for the primary class
     * @return batch result with redefined / skipped lists
     * @throws AmbiguousClassException if the primary class is ambiguous without loader id
     */
    public BatchHotSwapResult hotSwapBatch(Map<String, byte[]> bytecodeMap,
                                            String primaryClass,
                                            String classLoaderId,
                                            String operator,
                                            String reason,
                                            String precomputedDiff) {
        Instrumentation inst = InstrumentationHolder.get();

        if (!inst.isRedefineClassesSupported()) {
            HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class redefinition is not supported by this JVM");
            addHistory(record);
            return new BatchHotSwapResult(false, record, List.of(), List.of(), record.message());
        }

        // Resolve primary first so ambiguous primary fails fast with 409 at handler layer
        Class<?> primaryResolved = LoadedClassResolver.resolve(primaryClass, classLoaderId);
        if (primaryResolved == null) {
            HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.FAILED, "Class not found among loaded classes: " + primaryClass
                            + (classLoaderId != null ? " (classLoaderId=" + classLoaderId + ")" : ""));
            addHistory(record);
            return new BatchHotSwapResult(false, record, List.of(), List.of(), record.message());
        }

        String lockName = primaryClass;
        ReentrantLock lock = classLocks.computeIfAbsent(lockName, k -> new ReentrantLock());
        lock.lock();
        try {
            List<String> redefined = new ArrayList<>();
            List<Map<String, String>> skipped = new ArrayList<>();
            List<ClassDefinition> definitions = new ArrayList<>();
            List<Class<?>> toBackup = new ArrayList<>();

            for (Map.Entry<String, byte[]> entry : bytecodeMap.entrySet()) {
                String name = entry.getKey();
                byte[] bytes = entry.getValue();

                Class<?> target;
                try {
                    target = LoadedClassResolver.resolve(name, classLoaderId);
                } catch (AmbiguousClassException e) {
                    // Non-primary ambiguous without id — skip with reason (primary already validated)
                    skipped.add(skippedEntry(name, "ambiguous ClassLoader; specify classLoaderId"));
                    continue;
                }

                if (target == null) {
                    skipped.add(skippedEntry(name, "not loaded (anonymous/inner class may not be initialized yet)"));
                    continue;
                }
                if (!inst.isModifiableClass(target)) {
                    skipped.add(skippedEntry(name, "class is not modifiable"));
                    continue;
                }

                toBackup.add(target);
                definitions.add(new ClassDefinition(target, bytes));
                redefined.add(name);
            }

            if (definitions.isEmpty()) {
                HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                        HotSwapRecord.Status.FAILED,
                        "No loaded redefinable classes found in compile output for " + primaryClass);
                addHistory(record);
                return new BatchHotSwapResult(false, record, List.of(), skipped, record.message());
            }

            // Full backup before atomic redefine
            for (Class<?> clazz : toBackup) {
                try {
                    backupService.backup(clazz);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "Failed to backup bytecode for " + clazz.getName(), e);
                }
            }

            try {
                inst.redefineClasses(definitions.toArray(ClassDefinition[]::new));

                String msg = "Successfully redefined " + redefined.size() + " class(es)"
                        + (skipped.isEmpty() ? "" : ", skipped " + skipped.size());
                HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                        HotSwapRecord.Status.SUCCESS, msg, operator, reason, precomputedDiff);
                addHistory(record);
                LOG.info("Batch hot-swap successful: " + redefined);
                return new BatchHotSwapResult(true, record, List.copyOf(redefined), List.copyOf(skipped), msg);

            } catch (UnsupportedOperationException e) {
                String msg = "Structural change not supported: " + e.getMessage()
                        + ". JVM redefine cannot add/remove methods or fields.";
                HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                        HotSwapRecord.Status.FAILED, msg, operator, reason, null);
                addHistory(record);
                return new BatchHotSwapResult(false, record, List.of(), List.copyOf(skipped), msg);
            } catch (ClassFormatError e) {
                String msg = "Invalid class format: " + e.getMessage();
                HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                        HotSwapRecord.Status.FAILED, msg, operator, reason, null);
                addHistory(record);
                return new BatchHotSwapResult(false, record, List.of(), List.copyOf(skipped), msg);
            } catch (UnmodifiableClassException e) {
                String msg = "Class cannot be modified: " + e.getMessage();
                HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                        HotSwapRecord.Status.FAILED, msg, operator, reason, null);
                addHistory(record);
                return new BatchHotSwapResult(false, record, List.of(), List.copyOf(skipped), msg);
            } catch (Exception e) {
                String msg = "Hot-swap failed: " + e.getMessage();
                HotSwapRecord record = createRecord(primaryClass, HotSwapRecord.Action.HOTSWAP,
                        HotSwapRecord.Status.FAILED, msg, operator, reason, null);
                addHistory(record);
                return new BatchHotSwapResult(false, record, List.of(), List.copyOf(skipped), msg);
            }
        } finally {
            lock.unlock();
        }
    }

    private static Map<String, String> skippedEntry(String className, String reason) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("className", className);
        m.put("reason", reason);
        return m;
    }

    public HotSwapRecord rollback(String className) {
        return rollback(className, null, null, null);
    }

    public HotSwapRecord rollback(String className, String operator, String reason) {
        return rollback(className, operator, reason, null);
    }

    public HotSwapRecord rollback(String className, String operator, String reason, String classLoaderId) {
        ReentrantLock lock = classLocks.computeIfAbsent(className, k -> new ReentrantLock());
        lock.lock();
        try {
            return doRollback(className, operator, reason, classLoaderId);
        } finally {
            lock.unlock();
        }
    }

    private HotSwapRecord doRollback(String className, String operator, String reason, String classLoaderId) {
        Instrumentation inst = InstrumentationHolder.get();

        Optional<byte[]> backup;
        if (classLoaderId != null && !classLoaderId.isBlank()) {
            backup = backupService.getBackup(classLoaderId, className);
            if (backup.isEmpty()) {
                backup = backupService.getBackup(className);
            }
        } else {
            backup = backupService.getBackup(className);
        }
        if (backup.isEmpty()) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.ROLLBACK,
                    HotSwapRecord.Status.FAILED,
                    "No backup found for class: " + className + ". Cannot rollback.");
            addHistory(record);
            return record;
        }

        Class<?> targetClass = LoadedClassResolver.resolve(className, classLoaderId);
        if (targetClass == null) {
            HotSwapRecord record = createRecord(className, HotSwapRecord.Action.ROLLBACK,
                    HotSwapRecord.Status.FAILED,
                    "Class not found among loaded classes: " + className);
            addHistory(record);
            return record;
        }

        byte[] originalBytecode = backup.get();
        try {
            ClassDefinition definition = new ClassDefinition(targetClass, originalBytecode);
            inst.redefineClasses(definition);

            String key = BytecodeBackupService.backupKey(targetClass);
            if (!backupService.removeBackup(key)) {
                backupService.removeBackup(className);
            }

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

    public java.util.Set<String> getRollbackableClasses() {
        return backupService.getBackedUpClasses();
    }

    public List<HotSwapRecord> getHistory() {
        List<HotSwapRecord> snapshot = new ArrayList<>(history);
        Collections.reverse(snapshot);
        return Collections.unmodifiableList(snapshot);
    }

    public List<HotSwapRecord> getHistory(int limit) {
        List<HotSwapRecord> all = getHistory();
        if (limit >= all.size()) {
            return all;
        }
        return all.subList(0, limit);
    }

    public BytecodeBackupService getBackupService() {
        return backupService;
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
        while (!history.offerLast(record)) {
            history.pollFirst();
        }
    }
}
