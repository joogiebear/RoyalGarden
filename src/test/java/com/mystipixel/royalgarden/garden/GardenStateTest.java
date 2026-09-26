package com.mystipixel.royalgarden.garden;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GardenStateTest {

    private GardenState state;

    @BeforeEach
    void fresh() {
        GardenLayout.configure(5, 96, 100);
        state = new GardenState(UUID.randomUUID());
    }

    @Test
    void startsWithOnlyTheFarmhouse() {
        assertTrue(state.isUnlocked(2, 2));
        assertEquals(1, state.unlockedCount());
        assertEquals(0, state.purchasedCount());
    }

    @Test
    void unlocksMustTouchOrthogonally() {
        assertFalse(state.canUnlock(1, 1));                 // diagonal
        assertTrue(state.canUnlock(1, 2));
        assertTrue(state.unlock(1, 2));
        assertTrue(state.canUnlock(0, 2));
        assertFalse(state.unlock(1, 2));                    // already owned
        assertEquals(1, state.purchasedCount());
    }

    @Test
    void farmhouseCannotBeLocked() {
        assertFalse(state.lock(2, 2));
        state.forceUnlock(0, 0);
        assertTrue(state.lock(0, 0));
        assertFalse(state.isUnlocked(0, 0));
    }

    @Test
    void readOnlyStateRefusesUnlocks() {
        state.markReadOnly();
        assertFalse(state.unlock(1, 2));
    }

    @Test
    void pendingRebuildsAreTrackedPerPlot() {
        state.setPending(1, 2, "wheat");
        assertEquals(1, state.pending().size());
        state.setPending(1, 2, null);
        assertTrue(state.pending().isEmpty());
    }
}
