package com.mystipixel.royalgarden.level;

import com.mystipixel.royalgarden.milestone.Tiers;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LevelsTest {

    private static final long[] THRESHOLDS = {50, 120, 220};

    @Test
    void levelsStartAtOneAndClimbPerThreshold() {
        assertEquals(1, LevelConfig.levelOf(THRESHOLDS, 0));
        assertEquals(1, LevelConfig.levelOf(THRESHOLDS, 49));
        assertEquals(2, LevelConfig.levelOf(THRESHOLDS, 50));
        assertEquals(3, LevelConfig.levelOf(THRESHOLDS, 219));
        assertEquals(4, LevelConfig.levelOf(THRESHOLDS, 1_000_000));
        assertEquals(1, LevelConfig.levelOf(new long[0], 500));
    }

    @Test
    void defaultCurveGivesFifteenLevelsInAReachableRange() {
        long[] curve = Tiers.curve(50, 1.4, 14, 10);
        assertEquals(14, curve.length);
        for (int i = 1; i < curve.length; i++) {
            assertTrue(curve[i] > curve[i - 1]);
        }
        assertEquals(50, curve[0]);
        assertTrue(curve[13] > 12_000 && curve[13] < 16_000, "total was " + curve[13]);
    }
}
