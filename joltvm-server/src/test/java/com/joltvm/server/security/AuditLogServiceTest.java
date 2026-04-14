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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AuditLogService}.
 */
@DisplayName("AuditLogService")
class AuditLogServiceTest {

    @Nested
    @DisplayName("in-memory only")
    class InMemoryOnly {

        private AuditLogService service;

        @BeforeEach
        void setUp() {
            service = new AuditLogService();
        }

        @Test
        @DisplayName("initially empty")
        void initiallyEmpty() {
            assertEquals(0, service.size());
            assertTrue(service.getEntries().isEmpty());
        }

        @Test
        @DisplayName("records hot-swap operations")
        void recordsHotSwap() {
            HotSwapRecord record = new HotSwapRecord(
                    "id1", "com.example.Test",
                    HotSwapRecord.Action.HOTSWAP, HotSwapRecord.Status.SUCCESS,
                    "Success", Instant.now(), "admin", "fix bug #123",
                    "Original: 100 bytes → New: 120 bytes");

            service.record(record);

            assertEquals(1, service.size());
            AuditLogService.AuditEntry entry = service.getEntries().get(0);
            assertEquals("HOTSWAP_OPERATION", entry.eventType());
            assertEquals("admin", entry.operator());
            assertEquals("com.example.Test", entry.className());
            assertEquals("HOTSWAP", entry.action());
            assertEquals("SUCCESS", entry.status());
            assertEquals("fix bug #123", entry.reason());
            assertNotNull(entry.diff());
        }

        @Test
        @DisplayName("records security events")
        void recordsSecurityEvents() {
            service.recordSecurityEvent("LOGIN", "admin", "Login successful", "127.0.0.1");

            assertEquals(1, service.size());
            AuditLogService.AuditEntry entry = service.getEntries().get(0);
            assertEquals("LOGIN", entry.eventType());
            assertEquals("admin", entry.operator());
            assertEquals("127.0.0.1", entry.clientIp());
        }

        @Test
        @DisplayName("entries are ordered newest first")
        void newestFirst() {
            service.recordSecurityEvent("LOGIN", "user1", "1st", null);
            service.recordSecurityEvent("LOGIN", "user2", "2nd", null);

            List<AuditLogService.AuditEntry> entries = service.getEntries();
            assertEquals("user2", entries.get(0).operator());
            assertEquals("user1", entries.get(1).operator());
        }

        @Test
        @DisplayName("getEntries with limit")
        void getEntriesWithLimit() {
            for (int i = 0; i < 10; i++) {
                service.recordSecurityEvent("TEST", "user" + i, "msg", null);
            }
            assertEquals(10, service.size());
            assertEquals(5, service.getEntries(5).size());
            assertEquals(10, service.getEntries(20).size());
        }

        @Test
        @DisplayName("backward-compatible HotSwapRecord (no operator/reason/diff)")
        void backwardCompatible() {
            HotSwapRecord record = new HotSwapRecord(
                    "id2", "com.example.Old",
                    HotSwapRecord.Action.ROLLBACK, HotSwapRecord.Status.SUCCESS,
                    "Rolled back", Instant.now());

            service.record(record);
            AuditLogService.AuditEntry entry = service.getEntries().get(0);
            assertNull(entry.operator());
            assertNull(entry.reason());
            assertNull(entry.diff());
        }
    }

    @Nested
    @DisplayName("export")
    class Export {

        private AuditLogService service;

        @BeforeEach
        void setUp() {
            service = new AuditLogService();
            service.recordSecurityEvent("LOGIN", "admin", "OK", "10.0.0.1");
            service.record(new HotSwapRecord(
                    "id1", "com.test.Foo",
                    HotSwapRecord.Action.HOTSWAP, HotSwapRecord.Status.SUCCESS,
                    "Done", Instant.now(), "admin", "testing", "100→120"));
        }

        @Test
        @DisplayName("exportAsJsonLines produces valid output")
        void jsonLinesExport() {
            String output = service.exportAsJsonLines();
            assertFalse(output.isBlank());
            String[] lines = output.strip().split("\n");
            assertEquals(2, lines.length);
            assertTrue(lines[0].startsWith("{"));
            assertTrue(lines[0].endsWith("}"));
            assertTrue(lines[0].contains("\"eventType\":\"LOGIN\""));
            assertTrue(lines[1].contains("\"eventType\":\"HOTSWAP_OPERATION\""));
        }

        @Test
        @DisplayName("exportAsCsv produces valid output")
        void csvExport() {
            String output = service.exportAsCsv();
            assertFalse(output.isBlank());
            String[] lines = output.strip().split("\n");
            assertEquals(3, lines.length); // header + 2 data rows
            assertTrue(lines[0].startsWith("timestamp,"));
        }
    }

    @Nested
    @DisplayName("file persistence")
    class FilePersistence {

        @TempDir
        Path tempDir;

        @Test
        @DisplayName("writes entries to file")
        void writesToFile() throws IOException {
            Path logFile = tempDir.resolve("audit.jsonl");
            AuditLogService service = new AuditLogService(logFile);

            service.recordSecurityEvent("LOGIN", "admin", "OK", null);
            service.record(new HotSwapRecord(
                    "id1", "Test", HotSwapRecord.Action.HOTSWAP,
                    HotSwapRecord.Status.SUCCESS, "OK", Instant.now()));

            assertTrue(Files.exists(logFile));
            List<String> lines = Files.readAllLines(logFile);
            assertEquals(2, lines.size());
            assertTrue(lines.get(0).contains("LOGIN"));
            assertTrue(lines.get(1).contains("HOTSWAP_OPERATION"));
        }
    }

    @Nested
    @DisplayName("AuditEntry")
    class AuditEntryTest {

        @Test
        @DisplayName("toJsonLine escapes special characters")
        void jsonEscaping() {
            AuditLogService.AuditEntry entry = new AuditLogService.AuditEntry(
                    Instant.now(), "TEST", "admin", null, null, null,
                    "message with \"quotes\" and\nnewlines", null, null, null);
            String json = entry.toJsonLine();
            assertTrue(json.contains("\\\"quotes\\\""));
            assertTrue(json.contains("\\n"));
            assertFalse(json.contains("\n")); // raw newline should not appear
        }

        @Test
        @DisplayName("toJsonLine handles null fields")
        void nullFields() {
            AuditLogService.AuditEntry entry = new AuditLogService.AuditEntry(
                    Instant.now(), "TEST", null, null, null, null,
                    null, null, null, null);
            String json = entry.toJsonLine();
            assertTrue(json.contains("\"operator\":null"));
            assertTrue(json.contains("\"message\":null"));
        }
    }
}
