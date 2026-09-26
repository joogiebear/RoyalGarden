package com.mystipixel.royalgarden.config;

import com.mystipixel.royalskyblock.currency.Cost;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationsTest {

    @Test
    void parsesShorthand() {
        assertEquals(3_600_000L, Durations.parseMillis("1h"));
        assertEquals(5_400_000L, Durations.parseMillis("1h30m"));
        assertEquals(172_800_000L, Durations.parseMillis("2d"));
        assertEquals(45_000L, Durations.parseMillis("45"));
        assertEquals(0L, Durations.parseMillis("0"));
        assertEquals(0L, Durations.parseMillis("soon"));
        assertEquals(0L, Durations.parseMillis(null));
    }

    @Test
    void formatsAtMostTwoUnits() {
        assertEquals("1h 30m", Durations.format(5_400_000L));
        assertEquals("1d 1h", Durations.format(90_061_000L));
        assertEquals("0s", Durations.format(0));
        assertEquals("1s", Durations.format(1));
    }

    @Test
    void parsesCostsLikeTheHost() {
        Cost cost = GardenConfig.parseCost("5000 coins", "test", null);
        assertEquals("coins", cost.currency());
        assertEquals(5000.0, cost.amount());
        assertEquals("gems", GardenConfig.parseCost("50 gems", "test", null).currency());
        assertEquals("coins", GardenConfig.parseCost("75", "test", null).currency());
        assertTrue(GardenConfig.parseCost("lots", "test", null).isFree());
        assertTrue(GardenConfig.parseCost("", "test", null).isFree());
    }
}
