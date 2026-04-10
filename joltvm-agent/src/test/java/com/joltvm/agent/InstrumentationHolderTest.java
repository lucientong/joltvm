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

package com.joltvm.agent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link InstrumentationHolder}.
 */
class InstrumentationHolderTest {

    @AfterEach
    void tearDown() {
        // Reset the holder after each test to avoid interference between tests
        InstrumentationHolder.reset();
    }

    @Test
    @DisplayName("isAvailable returns false before set is called")
    void isAvailable_beforeSet_returnsFalse() {
        assertFalse(InstrumentationHolder.isAvailable());
    }

    @Test
    @DisplayName("get throws IllegalStateException before set is called")
    void get_beforeSet_throwsException() {
        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                InstrumentationHolder::get
        );
        assertTrue(exception.getMessage().contains("Instrumentation not available"));
    }

    @Test
    @DisplayName("set stores the Instrumentation instance")
    void set_storesInstance() {
        Instrumentation mockInst = mock(Instrumentation.class);

        InstrumentationHolder.set(mockInst);

        assertTrue(InstrumentationHolder.isAvailable());
        assertSame(mockInst, InstrumentationHolder.get());
    }

    @Test
    @DisplayName("set with null throws NullPointerException")
    void set_withNull_throwsException() {
        assertThrows(NullPointerException.class, () -> InstrumentationHolder.set(null));
    }

    @Test
    @DisplayName("duplicate set does not override the first instance")
    void set_duplicate_doesNotOverride() {
        Instrumentation first = mock(Instrumentation.class);
        Instrumentation second = mock(Instrumentation.class);

        InstrumentationHolder.set(first);
        InstrumentationHolder.set(second); // Should be a no-op (with warning logged)

        assertSame(first, InstrumentationHolder.get(), "First instance should be retained");
    }

    @Test
    @DisplayName("isAvailable returns true after set is called")
    void isAvailable_afterSet_returnsTrue() {
        Instrumentation mockInst = mock(Instrumentation.class);

        InstrumentationHolder.set(mockInst);

        assertTrue(InstrumentationHolder.isAvailable());
    }

    @Test
    @DisplayName("reset clears the stored instance")
    void reset_clearsInstance() {
        Instrumentation mockInst = mock(Instrumentation.class);
        InstrumentationHolder.set(mockInst);
        assertTrue(InstrumentationHolder.isAvailable());

        InstrumentationHolder.reset();

        assertFalse(InstrumentationHolder.isAvailable());
    }
}
