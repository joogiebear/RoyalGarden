package com.mystipixel.royalgarden.garden;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledFarmhouseTest {

    private static Schematic bundled() throws IOException {
        try (InputStream in = BundledFarmhouseTest.class.getClassLoader()
                .getResourceAsStream("royalgarden/farmbarn.schem")) {
            assertNotNull(in, "royalgarden/farmbarn.schem is not on the classpath");
            return Schematic.read(in);
        }
    }

    @Test
    void theBundledBarnFitsADefaultPlotAndMarksEverySpot() throws IOException {
        Schematic barn = bundled();
        assertTrue(barn.width() <= 96 && barn.length() <= 96, "wider than a default plot");
        for (String spot : Farmhouse.SPOTS) {
            assertTrue(barn.markers().containsKey(spot), "no [" + spot + "] sign");
        }
        assertEquals(Farmhouse.SPOTS.size(), barn.markers().size());
    }

    @Test
    void theGroundLayerIsFoundWithoutHelp() throws IOException {
        Schematic barn = bundled();
        int ground = barn.groundLayer();
        assertTrue(ground > 0 && ground < barn.height() - 1, "ground layer " + ground);
    }
}
