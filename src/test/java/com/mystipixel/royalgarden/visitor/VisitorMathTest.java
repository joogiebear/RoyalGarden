package com.mystipixel.royalgarden.visitor;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisitorMathTest {

    private record Weighted(String id, double weight) {
    }

    private static final List<Weighted> ENTRIES = List.of(
            new Weighted("common", 70), new Weighted("never", 0), new Weighted("rare", 30));

    @Test
    void weightedPickFollowsTheWeights() {
        assertEquals("common", VisitorMath.pickWeighted(ENTRIES, Weighted::weight, 0.0).id());
        assertEquals("common", VisitorMath.pickWeighted(ENTRIES, Weighted::weight, 0.69).id());
        assertEquals("rare", VisitorMath.pickWeighted(ENTRIES, Weighted::weight, 0.70).id());
        assertEquals("rare", VisitorMath.pickWeighted(ENTRIES, Weighted::weight, 0.9999).id());
        assertEquals("rare", VisitorMath.pickWeighted(ENTRIES, Weighted::weight, 1.0).id());
    }

    @Test
    void weightedPickSkipsUnweightedAndHandlesNothing() {
        assertNull(VisitorMath.pickWeighted(List.<Weighted>of(), Weighted::weight, 0.5));
        assertNull(VisitorMath.pickWeighted(List.of(new Weighted("a", 0), new Weighted("b", -3)),
                Weighted::weight, 0.5));
        assertEquals("b", VisitorMath.pickWeighted(List.of(new Weighted("a", Double.NaN), new Weighted("b", 1)),
                Weighted::weight, 0.0).id());
    }

    @Test
    void weightedPickIsRoughlyProportional() {
        Random random = new Random(42);
        int rare = 0;
        for (int i = 0; i < 10_000; i++) {
            if (VisitorMath.pickWeighted(ENTRIES, Weighted::weight, random.nextDouble()).id().equals("rare")) {
                rare++;
            }
        }
        assertTrue(rare > 2_700 && rare < 3_300, "rare was picked " + rare + " times");
    }

    @Test
    void rangesParseAndRollInside() {
        assertEquals(new VisitorMath.Range(64, 64), VisitorMath.Range.parse("64"));
        assertEquals(new VisitorMath.Range(32, 64), VisitorMath.Range.parse("32-64"));
        assertEquals(new VisitorMath.Range(32, 64), VisitorMath.Range.parse("64 - 32"));
        assertEquals(new VisitorMath.Range(1000, 1000), VisitorMath.Range.parse("1,000"));
        assertEquals(new VisitorMath.Range(5, 5), VisitorMath.Range.parse(5));
        assertNull(VisitorMath.Range.parse("lots"));
        assertNull(VisitorMath.Range.parse(""));
        assertNull(VisitorMath.Range.parse(null));

        Random random = new Random(7);
        VisitorMath.Range range = new VisitorMath.Range(3, 6);
        for (int i = 0; i < 500; i++) {
            long rolled = range.roll(random);
            assertTrue(rolled >= 3 && rolled <= 6);
        }
        assertEquals(new VisitorMath.Range(11, 13), new VisitorMath.Range(1, 3).plus(new VisitorMath.Range(10, 10)));
    }

    @Test
    void requestsScaleWithLevelUpToTheCap() {
        assertEquals(1.0, VisitorMath.multiplier(1, 0.1, 2.0));
        assertEquals(1.5, VisitorMath.multiplier(6, 0.1, 2.0), 1e-9);
        assertEquals(2.0, VisitorMath.multiplier(30, 0.1, 2.0));
        assertEquals(3.9, VisitorMath.multiplier(30, 0.1, 0), 1e-9);    // no cap
        assertEquals(1.0, VisitorMath.multiplier(10, -1, 2.0));

        assertEquals(96, VisitorMath.scale(64, 1.5));
        assertEquals(1, VisitorMath.scale(1, 0.1));
        assertEquals(0, VisitorMath.scale(0, 3));
    }

    @Test
    void byLevelTakesTheHighestStepReached() {
        TreeMap<Integer, Integer> table = new TreeMap<>();
        table.put(5, 6);
        table.put(10, 7);
        assertEquals(5, VisitorMath.byLevel(table, 1, 5));
        assertEquals(6, VisitorMath.byLevel(table, 5, 5));
        assertEquals(6, VisitorMath.byLevel(table, 9, 5));
        assertEquals(7, VisitorMath.byLevel(table, 15, 5));
        assertEquals(5, VisitorMath.byLevel(null, 15, 5));
    }

    @Test
    void arrivalsComeOncePerInterval() {
        VisitorMath.Due due = VisitorMath.due(0, 250, 100, 0, 5);
        assertEquals(2, due.count());
        assertEquals(200, due.lastArrival());      // the 50 left over counts toward the next one

        due = VisitorMath.due(1000, 1099, 100, 0, 5);
        assertEquals(0, due.count());
        assertEquals(1000, due.lastArrival());
    }

    @Test
    void aFullQueueStopsTheClock() {
        VisitorMath.Due due = VisitorMath.due(0, 10_000, 100, 5, 5);
        assertEquals(0, due.count());
        assertEquals(0, due.lastArrival());
    }

    @Test
    void overflowIsDroppedAndTheClockRestarts() {
        VisitorMath.Due due = VisitorMath.due(0, 1_050, 100, 3, 5);
        assertEquals(2, due.count());
        assertEquals(1_050, due.lastArrival());

        due = VisitorMath.due(0, 250, 100, 3, 5);   // exactly enough room
        assertEquals(2, due.count());
        assertEquals(200, due.lastArrival());
    }

    @Test
    void offlineTimeCountsAtTheConfiguredRate() {
        assertEquals(1_000, VisitorMath.creditOffline(1_000, 5_000, 9_000, 1.0));
        assertEquals(5_000, VisitorMath.creditOffline(1_000, 5_000, 9_000, 0.0));
        assertEquals(3_000, VisitorMath.creditOffline(1_000, 5_000, 9_000, 0.5));
        assertEquals(1_000, VisitorMath.creditOffline(1_000, 0, 9_000, 0.0));     // never recorded
        assertEquals(5_000, VisitorMath.creditOffline(1_000, 5_000, 9_000, -2));  // clamped to 0
    }
}
