package com.mystipixel.royalgarden.pest;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PestMathTest {

    private static final long MINUTE = 60_000;

    @Test
    void cooldownRunsFromTheLastPest() {
        assertTrue(PestMath.offCooldown(0, 1_000, MINUTE));             // never had one
        assertFalse(PestMath.offCooldown(10 * MINUTE, 10 * MINUTE + 59_999, MINUTE));
        assertTrue(PestMath.offCooldown(10 * MINUTE, 11 * MINUTE, MINUTE));
        assertTrue(PestMath.offCooldown(10 * MINUTE, 10 * MINUTE, 0));   // no cooldown
        assertTrue(PestMath.offCooldown(10 * MINUTE, 5 * MINUTE, MINUTE)); // clock went back: don't lock up
    }

    @Test
    void chanceIsAPercent() {
        assertTrue(PestMath.rolls(2.5, 0.024));
        assertFalse(PestMath.rolls(2.5, 0.025));
        assertFalse(PestMath.rolls(0, 0.0));
        assertTrue(PestMath.rolls(100, 0.9999));
    }

    @Test
    void capsPerPlotAndPerGarden() {
        assertTrue(PestMath.hasRoom(2, 5, 3, 8));
        assertFalse(PestMath.hasRoom(3, 5, 3, 8));
        assertFalse(PestMath.hasRoom(0, 8, 3, 8));
        assertTrue(PestMath.hasRoom(50, 500, 0, 0));                     // no caps
    }

    @Test
    void infestedFromTheThreshold() {
        assertFalse(PestMath.infested(0, 1));
        assertTrue(PestMath.infested(1, 1));
        assertFalse(PestMath.infested(2, 3));
        assertTrue(PestMath.infested(4, 3));
        assertFalse(PestMath.infested(9, 0));                             // switched off
    }

    @Test
    void landsOnTheHarvestedPlotByDefault() {
        assertEquals(7, PestMath.pickPlot(7, List.of(6, 7, 8), Map.of(), 3, 8, 25, 0.5, 0.0));
    }

    @Test
    void sometimesLandsOnAnotherPlot() {
        // otherRoll under 25% sends it elsewhere; pickRoll picks evenly among the others.
        assertEquals(6, PestMath.pickPlot(7, List.of(6, 7, 8), Map.of(), 3, 8, 25, 0.1, 0.0));
        assertEquals(8, PestMath.pickPlot(7, List.of(6, 7, 8), Map.of(), 3, 8, 25, 0.1, 0.99));
    }

    @Test
    void aFullPlotSendsItElsewhere() {
        assertEquals(8, PestMath.pickPlot(7, List.of(6, 7, 8), Map.of(7, 3, 6, 3), 3, 8, 0, 0.9, 0.0));
    }

    @Test
    void aFullGardenSendsItNowhere() {
        assertEquals(-1, PestMath.pickPlot(7, List.of(6, 7, 8), Map.of(6, 3, 7, 3, 8, 2), 3, 8, 0, 0.9, 0.0));
        assertEquals(-1, PestMath.pickPlot(7, List.of(7), Map.of(7, 3), 3, 8, 0, 0.9, 0.0));
    }

    @Test
    void anIneligibleHarvestedPlotIsNeverPicked() {
        // Farming the farmhouse plot, which pests may not land on.
        assertEquals(6, PestMath.pickPlot(12, List.of(6), Map.of(), 3, 8, 0, 0.9, 0.5));
        assertEquals(-1, PestMath.pickPlot(12, List.of(), Map.of(), 3, 8, 0, 0.9, 0.5));
    }

    @Test
    void otherPlotFallsBackToTheHarvestedOneWhenNothingElseFits() {
        assertEquals(7, PestMath.pickPlot(7, List.of(6, 7), Map.of(6, 3), 3, 8, 100, 0.0, 0.0));
    }

    @Test
    void spotsStayInsideThePlotMargin() {
        assertEquals(-44, PestMath.spot(-48, 96, 4, 0.0));
        assertEquals(43, PestMath.spot(-48, 96, 4, 0.99999));
        assertEquals(10, PestMath.spot(10, 1, 4, 0.7));                  // margin wider than the plot
    }

    @Test
    void outsideIsByColumn() {
        assertFalse(PestMath.outside(-48.0, 47.9, -48, 47, -48, 47));
        assertTrue(PestMath.outside(48.0, 0, -48, 47, -48, 47));
        assertTrue(PestMath.outside(0, -48.01, -48, 47, -48, 47));
    }
}
