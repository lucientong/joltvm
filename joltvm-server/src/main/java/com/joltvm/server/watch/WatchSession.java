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

package com.joltvm.server.watch;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Represents a single watch session that observes method invocations.
 *
 * <p>Each session has its own record buffer, configuration, and lifecycle.
 * Records are stored in a bounded list (max {@value MAX_RECORDS}).
 */
public class WatchSession {

    /** Maximum records per session. */
    static final int MAX_RECORDS = 1000;

    private final String id;
    private final String classPattern;
    private final String methodPattern;
    private final String conditionExpr; // optional OGNL condition
    private final int maxRecords;
    private final long expireAtMs;
    private final Instant createdAt;

    private final CopyOnWriteArrayList<WatchRecord> records = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalMatched = new AtomicInteger(0);
    private volatile boolean active = true;

    public WatchSession(String classPattern, String methodPattern,
                        String conditionExpr, int maxRecords, long durationMs) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.classPattern = classPattern;
        this.methodPattern = methodPattern;
        this.conditionExpr = conditionExpr;
        this.maxRecords = Math.min(maxRecords > 0 ? maxRecords : MAX_RECORDS, MAX_RECORDS);
        this.expireAtMs = System.currentTimeMillis() + durationMs;
        this.createdAt = Instant.now();
    }

    /** For testing — allows specifying ID. */
    WatchSession(String id, String classPattern, String methodPattern,
                 String conditionExpr, int maxRecords, long durationMs) {
        this.id = id;
        this.classPattern = classPattern;
        this.methodPattern = methodPattern;
        this.conditionExpr = conditionExpr;
        this.maxRecords = Math.min(maxRecords > 0 ? maxRecords : MAX_RECORDS, MAX_RECORDS);
        this.expireAtMs = System.currentTimeMillis() + durationMs;
        this.createdAt = Instant.now();
    }

    /**
     * Adds a record if the session is active and hasn't exceeded its max records.
     *
     * @return true if the record was added
     */
    public boolean addRecord(WatchRecord record) {
        if (!active) return false;
        totalMatched.incrementAndGet();

        if (records.size() >= maxRecords) {
            // Evict oldest
            if (!records.isEmpty()) {
                records.remove(0);
            }
        }
        records.add(record);
        return true;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expireAtMs;
    }

    public void stop() {
        this.active = false;
    }

    public List<WatchRecord> getRecords() {
        return new ArrayList<>(records);
    }

    public List<WatchRecord> getRecordsSince(int index) {
        if (index < 0 || index >= records.size()) {
            return new ArrayList<>(records);
        }
        return new ArrayList<>(records.subList(index, records.size()));
    }

    public Map<String, Object> toSummaryMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("classPattern", classPattern);
        map.put("methodPattern", methodPattern);
        if (conditionExpr != null) map.put("conditionExpr", conditionExpr);
        map.put("recordCount", records.size());
        map.put("totalMatched", totalMatched.get());
        map.put("maxRecords", maxRecords);
        map.put("active", active);
        map.put("expired", isExpired());
        map.put("createdAt", createdAt.toString());
        return map;
    }

    public String getId() { return id; }
    public String getClassPattern() { return classPattern; }
    public String getMethodPattern() { return methodPattern; }
    public String getConditionExpr() { return conditionExpr; }
    public boolean isActive() { return active && !isExpired(); }
    public Instant getCreatedAt() { return createdAt; }
}
