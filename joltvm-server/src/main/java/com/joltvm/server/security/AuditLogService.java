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
 * <p>Maintains an in-memory audit log (for fast access) and optionally
 * appends entries to a file for persistence across JVM restarts.
 *
 * <p>The audit log file format is JSON Lines (one JSON object per line),
 * suitable for ingestion by log aggregation tools.
 */
public final class AuditLogService {

    private static final Logger LOG = Logger.getLogger(AuditLogService.class.getName());
    private static final int MAX_MEMORY_ENTRIES = 1000;
    private static final DateTimeFormatter ISO_FORMATTER =
            DateTimeFormatter.ISO_INSTANT;

    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();
    private final Path logFile;

    /**
     * Creates an audit log service without file persistence.
     */
    public AuditLogService() {
        this(null);
    }

    /**
     * Creates an audit log service with file persistence.
     *
     * @param logFile path to the audit log file (null for memory-only)
     */
    public AuditLogService(Path logFile) {
        this.logFile = logFile;
        if (logFile != null) {
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
     * @return JSON Lines formatted string
     */
    public String exportAsJsonLines() {
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

        // Trim old entries
        while (entries.size() > MAX_MEMORY_ENTRIES) {
            entries.remove(0);
        }

        // Persist to file
        if (logFile != null) {
            try (BufferedWriter writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                writer.write(entry.toJsonLine());
                writer.newLine();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Failed to write audit log entry", e);
            }
        }
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
