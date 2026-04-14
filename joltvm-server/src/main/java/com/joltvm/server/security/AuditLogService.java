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

package com.joltvm.server.security;

import com.joltvm.server.hotswap.HotSwapRecord;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service for persisting and exporting audit logs.
 *
 * <p>Maintains an in-memory audit log (for fast access) and appends entries to a
 * JSON Lines file for persistence across JVM restarts.
 *
 * <p>When no explicit path is provided, the service defaults to
 * {@code $java.io.tmpdir/joltvm-audit.jsonl}. File rotation is applied automatically:
 * once the active file exceeds {@value #MAX_FILE_SIZE_BYTES} bytes, it is renamed to
 * {@code .1} (the previous {@code .1} becomes {@code .2}, etc., up to
 * {@value #MAX_ROTATED_FILES} retained files).
 *
 * <p>The file format is JSON Lines (one JSON object per line), suitable for log
 * aggregation tools (Fluentd, Logstash, etc.).
 */
public final class AuditLogService {

    private static final Logger LOG = Logger.getLogger(AuditLogService.class.getName());
    private static final int MAX_MEMORY_ENTRIES = 1000;

    /** Maximum file size before rotation is triggered (10 MB). */
    static final long MAX_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    /** Number of rotated history files to retain. */
    static final int MAX_ROTATED_FILES = 3;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();
    private final Path logFile;

    /**
     * Creates an audit log service with the default file path
     * ({@code $java.io.tmpdir/joltvm-audit.jsonl}).
     */
    public AuditLogService() {
        this(null);
    }

    /**
     * Creates an audit log service that persists to the default path
     * ({@code $TMPDIR/joltvm-audit.jsonl}).
     *
     * <p>Use this factory method instead of the no-arg constructor when running
     * as an embedded agent and no explicit {@code auditFile} argument was provided.
     *
     * @return a new service backed by the default file path
     */
    public static AuditLogService createWithDefaultPath() {
        return new AuditLogService(
                Paths.get(System.getProperty("java.io.tmpdir"), "joltvm-audit.jsonl"));
    }

    /**
     * Creates an audit log service with an explicit file path, or memory-only if {@code null}.
     *
     * @param logFile path to the audit log file, or {@code null} for memory-only
     */
    public AuditLogService(Path logFile) {
        this.logFile = logFile;
        if (logFile != null && logFile.getParent() != null) {
            try {
                Files.createDirectories(logFile.getParent());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to create audit log directory", e);
            }
        }
    }

    /**
     * Records a hot-swap/rollback operation to the audit log.
     *
     * @param record the hot-swap record to log
     */
    public void record(HotSwapRecord record) {
        AuditEntry entry = AuditEntry.fromHotSwapRecord(record);
        addEntry(entry);
    }

    /**
     * Records a security event (login, logout, access denied, etc.).
     *
     * @param eventType   the type of event (e.g., "LOGIN", "LOGOUT", "ACCESS_DENIED")
     * @param username    the user involved (may be null)
     * @param message     additional details
     * @param clientIp    the client IP address (may be null)
     */
    public void recordSecurityEvent(String eventType, String username,
                                     String message, String clientIp) {
        AuditEntry entry = new AuditEntry(
                Instant.now(),
                eventType,
                username,
                null,   // className
                null,   // action
                null,   // status
                message,
                null,   // reason
                null,   // diff
                clientIp
        );
        addEntry(entry);
    }

    /**
     * Returns all audit entries (newest first).
     *
     * @return unmodifiable list of audit entries
     */
    public List<AuditEntry> getEntries() {
        List<AuditEntry> reversed = new ArrayList<>(entries);
        Collections.reverse(reversed);
        return Collections.unmodifiableList(reversed);
    }

    /**
     * Returns audit entries, newest first, limited to the given count.
     *
     * @param limit maximum entries to return
     * @return unmodifiable list of audit entries
     */
    public List<AuditEntry> getEntries(int limit) {
        List<AuditEntry> all = getEntries();
        if (limit >= all.size()) return all;
        return all.subList(0, limit);
    }

    /**
     * Returns the total number of audit entries in memory.
     *
     * @return entry count
     */
    public int size() {
        return entries.size();
    }

    /**
     * Exports all entries as JSON Lines string.
     *
     * <p>When file persistence is active, returns the file content (which contains all
     * historical entries including those evicted from the in-memory buffer). Falls back
     * to the in-memory buffer when no file is configured or the file cannot be read.
     *
     * @return JSON Lines formatted string
     */
    public String exportAsJsonLines() {
        if (logFile != null && Files.exists(logFile)) {
            try {
                return Files.readString(logFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to read audit log file for export", e);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (AuditEntry entry : entries) {
            sb.append(entry.toJsonLine()).append('\n');
        }
        return sb.toString();
    }

    /**
     * Exports entries as CSV string.
     *
     * @return CSV formatted string
     */
    public String exportAsCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("timestamp,eventType,operator,className,action,status,message,reason,diff,clientIp\n");
        for (AuditEntry entry : entries) {
            sb.append(csvEscape(ISO_FORMATTER.format(entry.timestamp))).append(',');
            sb.append(csvEscape(entry.eventType)).append(',');
            sb.append(csvEscape(entry.operator)).append(',');
            sb.append(csvEscape(entry.className)).append(',');
            sb.append(csvEscape(entry.action)).append(',');
            sb.append(csvEscape(entry.status)).append(',');
            sb.append(csvEscape(entry.message)).append(',');
            sb.append(csvEscape(entry.reason)).append(',');
            sb.append(csvEscape(entry.diff)).append(',');
            sb.append(csvEscape(entry.clientIp)).append('\n');
        }
        return sb.toString();
    }

    private void addEntry(AuditEntry entry) {
        entries.add(entry);

        // Trim old entries from in-memory buffer
        while (entries.size() > MAX_MEMORY_ENTRIES) {
            entries.remove(0);
        }

        // Persist to file with rotation
        if (logFile != null) {
            try {
                rotateIfNeeded();
                try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    writer.write(entry.toJsonLine());
                    writer.newLine();
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to write audit log entry", e);
            }
        }
    }

    /**
     * Rotates the log file if it has exceeded {@value #MAX_FILE_SIZE_BYTES} bytes.
     *
     * <p>Rotation renames the current file: {@code .jsonl} → {@code .jsonl.1}, shifting
     * existing rotated files down. Files beyond {@value #MAX_ROTATED_FILES} are deleted.
     */
    private void rotateIfNeeded() throws IOException {
        if (logFile == null || !Files.exists(logFile)) return;
        long size = Files.size(logFile);
        if (size < MAX_FILE_SIZE_BYTES) return;

        LOG.info("Rotating audit log: " + logFile + " (" + size + " bytes)");

        // Shift existing rotated files: .2 → deleted, .1 → .2, current → .1
        for (int i = MAX_ROTATED_FILES; i >= 1; i--) {
            Path rotated = Paths.get(logFile + "." + i);
            if (Files.exists(rotated)) {
                if (i == MAX_ROTATED_FILES) {
                    Files.delete(rotated);
                } else {
                    Files.move(rotated, Paths.get(logFile + "." + (i + 1)),
                            StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        Files.move(logFile, Paths.get(logFile + ".1"), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * A single audit log entry.
     */
    public record AuditEntry(
            Instant timestamp,
            String eventType,
            String operator,
            String className,
            String action,
            String status,
            String message,
            String reason,
            String diff,
            String clientIp
    ) {
        /**
         * Creates an audit entry from a hot-swap record.
         */
        static AuditEntry fromHotSwapRecord(HotSwapRecord record) {
            return new AuditEntry(
                    record.timestamp(),
                    "HOTSWAP_OPERATION",
                    record.operator(),
                    record.className(),
                    record.action() != null ? record.action().name() : null,
                    record.status() != null ? record.status().name() : null,
                    record.message(),
                    record.reason(),
                    record.diff(),
                    null  // clientIp not available from HotSwapRecord
            );
        }

        /**
         * Serializes this entry as a JSON string (single line).
         */
        String toJsonLine() {
            StringBuilder sb = new StringBuilder("{");
            sb.append("\"timestamp\":\"").append(ISO_FORMATTER.format(timestamp)).append('"');
            appendField(sb, "eventType", eventType);
            appendField(sb, "operator", operator);
            appendField(sb, "className", className);
            appendField(sb, "action", action);
            appendField(sb, "status", status);
            appendField(sb, "message", message);
            appendField(sb, "reason", reason);
            appendField(sb, "diff", diff);
            appendField(sb, "clientIp", clientIp);
            sb.append('}');
            return sb.toString();
        }

        private static void appendField(StringBuilder sb, String key, String value) {
            sb.append(",\"").append(key).append("\":");
            if (value == null) {
                sb.append("null");
            } else {
                sb.append('"').append(jsonEscape(value)).append('"');
            }
        }

        private static String jsonEscape(String s) {
            return s.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                    .replace("\t", "\\t");
        }
    }
}
