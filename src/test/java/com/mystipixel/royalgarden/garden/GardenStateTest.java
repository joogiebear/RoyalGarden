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

    @Test
    void buyingAnOvergrownPlotStartsClearingIt() {
        state.setOvergrowth(1, 2, 200);
        assertTrue(state.isOvergrown(1, 2));
        assertTrue(state.unlock(1, 2));
        assertFalse(state.isOvergrown(1, 2));
        assertTrue(state.isClearing(1, 2));
        assertEquals(200, state.clearingTotal(1, 2));
        assertEquals(0, state.clearingPercent(1, 2));
    }

    @Test
    void aPlotWithNoDebrisIsReadyAtOnce() {
        assertTrue(state.unlock(1, 2));
        assertFalse(state.isClearing(1, 2));
        assertEquals(100, state.clearingPercent(1, 2));
    }

    @Test
    void clearingProgressIsHeldBetweenNoneAndAll() {
        state.setOvergrowth(1, 2, 200);
        state.unlock(1, 2);
        state.setClearingDone(1, 2, 179);
        assertEquals(89, state.clearingPercent(1, 2));      // rounded down: 89.5% is not yet 90
        state.setClearingDone(1, 2, 500);
        assertEquals(200, state.clearingDone(1, 2));
        state.setClearingDone(1, 2, -5);
        assertEquals(0, state.clearingDone(1, 2));
    }

    @Test
    void debrisGrownAfterThePlotWasBoughtStartsClearing() {
        state.unlock(1, 2);
        state.setOvergrowth(1, 2, 50);
        assertTrue(state.isClearing(1, 2));
        assertFalse(state.isOvergrown(1, 2));
    }

    @Test
    void relockingKeepsWhatIsLeftToClear() {
        state.setOvergrowth(1, 2, 200);
        state.unlock(1, 2);
        state.setClearingDone(1, 2, 150);
        assertTrue(state.lock(1, 2));
        assertFalse(state.isClearing(1, 2));
        state.unlock(1, 2);
        assertEquals(50, state.clearingTotal(1, 2));
    }

    @Test
    void finishingOrRebuildingDropsTheRecord() {
        state.setOvergrowth(1, 2, 200);
        state.unlock(1, 2);
        assertTrue(state.finishClearing(1, 2));
        assertFalse(state.finishClearing(1, 2));
        state.setOvergrowth(0, 0, 10);
        state.forgetDebris(0, 0);
        assertFalse(state.isOvergrown(0, 0));
    }
}
