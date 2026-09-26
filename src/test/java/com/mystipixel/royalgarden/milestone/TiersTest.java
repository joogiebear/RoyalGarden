package com.mystipixel.royalgarden.milestone;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TiersTest {

    @Test
    void curveGivesRunningTotalsThatGrow() {
        long[] tiers = Tiers.curve(30, 1.5, 4, 10);
        // steps 30, 45→50 (rounded to 10), 67.5→70, 101.25→100
        assertArrayEquals(new long[]{30, 80, 150, 250}, tiers);
    }

    @Test
    void explicitDropsAnythingThatDoesNotIncrease() {
        assertArrayEquals(new long[]{10, 20, 40}, Tiers.explicit(List.of(10, 20, 15, 40, 40)));
    }

    @Test
    void tierOfCountsThresholdsReached() {
        MilestoneCrop crop = new MilestoneCrop("wheat", "Wheat", Material.WHEAT, Set.of(Material.WHEAT),
                Set.of(Material.WHEAT), 1, true, true, false, new long[]{30, 80, 150});
        assertEquals(0, crop.tierOf(29));
        assertEquals(1, crop.tierOf(30));
        assertEquals(2, crop.tierOf(149));
        assertEquals(3, crop.tierOf(10_000));
        assertEquals(80, crop.nextThreshold(1));
        assertEquals(-1, crop.nextThreshold(3));
        assertEquals(30, crop.tierStart(1));
    }

    @Test
    void romanAndBar() {
        assertEquals("XXIV", Tiers.roman(24));
        assertEquals("XLVI", Tiers.roman(46));
        assertEquals("0", Tiers.roman(0));
        assertEquals("##--", Tiers.bar(0.5, 4, "#", "-"));
        assertEquals("####", Tiers.bar(2.0, 4, "#", "-"));
    }
}
