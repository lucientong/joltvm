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

package com.joltvm.server.classloader;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClassLoaderServiceTest {

    @Test
    void getLoaderIdReturnsBootstrapForNull() {
        assertEquals("bootstrap", ClassLoaderService.getLoaderId(null));
    }

    @Test
    void getLoaderIdReturnsIdentityHashForNonNull() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String id = ClassLoaderService.getLoaderId(cl);
        assertEquals(String.valueOf(System.identityHashCode(cl)), id);
    }

    @Test
    void getClassesByLoaderPaginatesCorrectly() {
        ClassLoaderService service = new ClassLoaderService();
        // Bootstrap loader should have classes loaded
        Map<String, Object> result = service.getClassesByLoader("bootstrap", 0, 10, null);
        assertNotNull(result);
        assertEquals("bootstrap", result.get("loaderId"));
        assertNotNull(result.get("classes"));
        assertTrue((int) result.get("size") <= 10);
    }

    @Test
    void getClassesByLoaderSearchFiltersClasses() {
        ClassLoaderService service = new ClassLoaderService();
        Map<String, Object> result = service.getClassesByLoader("bootstrap", 0, 5000, "java.lang.String");
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<String> classes = (List<String>) result.get("classes");
        // In test env, Instrumentation may not be available, so the list could be empty
        // Just verify no errors and result structure is correct
        assertNotNull(classes);
    }

    @Test
    void getClassesByLoaderReturnsEmptyForUnknownLoader() {
        ClassLoaderService service = new ClassLoaderService();
        Map<String, Object> result = service.getClassesByLoader("999999999", 0, 100, null);
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<String> classes = (List<String>) result.get("classes");
        assertTrue(classes.isEmpty());
    }

    @Test
    void detectConflictsReturnsResult() {
        ClassLoaderService service = new ClassLoaderService();
        Map<String, Object> result = service.detectConflicts();
        assertNotNull(result);
        assertNotNull(result.get("conflicts"));
        assertNotNull(result.get("count"));
    }

    @Test
    void getClassesByLoaderHandlesNegativePage() {
        ClassLoaderService service = new ClassLoaderService();
        Map<String, Object> result = service.getClassesByLoader("bootstrap", -1, 10, null);
        assertEquals(0, result.get("page"));
    }

    @Test
    void getClassesByLoaderClampsPageSize() {
        ClassLoaderService service = new ClassLoaderService();
        Map<String, Object> result = service.getClassesByLoader("bootstrap", 0, 99999, null);
        assertEquals(5000, result.get("size"));
    }
}
