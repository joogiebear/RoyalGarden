package com.mystipixel.royalgarden.garden;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class GardenLayoutTest {

    @BeforeEach
    void defaults() {
        assertNull(GardenLayout.configure(5, 96, 100));
    }

    @Test
    void centrePlotIsCentredOnTheOrigin() {
        assertEquals(-48, GardenLayout.plotMin(2));
        assertEquals(47, GardenLayout.plotMax(2));
        assertEquals(-240, GardenLayout.boardMin());
        assertEquals(239, GardenLayout.boardMax());
        assertEquals(480, GardenLayout.span());
    }

    @Test
    void theFarmhouseIsNotAFarmablePlot() {
        assertEquals(25, GardenLayout.plotCount());
        assertEquals(24, GardenLayout.farmableCount());
    }

    @Test
    void plotAtRoundsTowardNegativeInfinity() {
        assertEquals(2, GardenLayout.plotAt(0));
        assertEquals(2, GardenLayout.plotAt(-48));
        assertEquals(1, GardenLayout.plotAt(-49));
        assertEquals(0, GardenLayout.plotAt(-240));
        assertEquals(-1, GardenLayout.plotAt(-241));
        assertEquals(4, GardenLayout.plotAt(239));
        assertEquals(-1, GardenLayout.plotAt(240));
    }

    @Test
    void namesRoundTrip() {
        assertEquals("C3", GardenLayout.plotName(2, 2));
        assertEquals("A1", GardenLayout.plotName(0, 0));
        assertArrayEquals(new int[]{4, 4}, GardenLayout.parsePlot("e5"));
        assertNull(GardenLayout.parsePlot("F1"));
        assertNull(GardenLayout.parsePlot("A0"));
        assertNull(GardenLayout.parsePlot("x"));
    }

    @Test
    void rejectsBoardsWithoutAMiddleOrTooBigForTheMenu() {
        assertNotNull(GardenLayout.configure(4, 96, 100));
        assertNotNull(GardenLayout.configure(7, 96, 100));
        assertNotNull(GardenLayout.configure(5, 16, 100));
        assertEquals(5, GardenLayout.grid());           // a rejected shape changes nothing
        assertNull(GardenLayout.configure(3, 64, 80));
        assertEquals(1, GardenLayout.centre());
        assertEquals(-32, GardenLayout.plotMin(1));
    }
}
