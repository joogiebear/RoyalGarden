package com.mystipixel.royalgarden.garden;

import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OvergrowthTest {

    private static final int SIZE = 96;
    private static final int GROUND = 100;

    private static final Overgrowth.Settings SETTINGS = new Overgrowth.Settings(true, 90, 3,
            14, List.of("oak", "birch", "spruce"),
            10, List.of(Material.STONE, Material.COBBLESTONE),
            12, List.of(Material.AZALEA_LEAVES),
            6,
            0.06, List.of(Material.SHORT_GRASS, Material.POPPY),
            List.of(), List.of());

    @Test
    void theSamePlotPlansTheSameDebris() {
        assertEquals(Set.copyOf(Overgrowth.plan(42, SIZE, GROUND, SETTINGS).voxels()),
                Set.copyOf(Overgrowth.plan(42, SIZE, GROUND, SETTINGS).voxels()));
    }

    @Test
    void differentPlotsDiffer() {
        assertFalse(Set.copyOf(Overgrowth.plan(1, SIZE, GROUND, SETTINGS).voxels())
                .equals(Set.copyOf(Overgrowth.plan(2, SIZE, GROUND, SETTINGS).voxels())));
    }

    @Test
    void everythingStaysInsideTheMarginAndAboveTheGround() {
        for (long seed = 0; seed < 20; seed++) {
            for (Overgrowth.Voxel voxel : Overgrowth.plan(seed, SIZE, GROUND, SETTINGS).voxels()) {
                assertTrue(voxel.x() >= 3 && voxel.x() <= SIZE - 4, "x " + voxel);
                assertTrue(voxel.z() >= 3 && voxel.z() <= SIZE - 4, "z " + voxel);
                assertTrue(voxel.y() > GROUND, "y " + voxel);
                assertTrue(voxel.y() <= GROUND + Overgrowth.Settings.MAX_HEIGHT, "height " + voxel);
            }
        }
    }

    @Test
    void noTwoBlocksShareAPosition() {
        List<Overgrowth.Voxel> voxels = Overgrowth.plan(7, SIZE, GROUND, SETTINGS).voxels();
        Set<Long> seen = new HashSet<>();
        for (Overgrowth.Voxel voxel : voxels) {
            assertTrue(seen.add(Overgrowth.key(voxel.x(), voxel.y(), voxel.z())), "overlap at " + voxel);
        }
    }

    @Test
    void everyMaterialPlannedCountsAsDebris() {
        Set<Material> debris = SETTINGS.materials();
        for (Overgrowth.Voxel voxel : Overgrowth.plan(9, SIZE, GROUND, SETTINGS).voxels()) {
            assertTrue(debris.contains(voxel.material()), voxel.material() + " would never count as cleared");
        }
    }

    // A leaf's distance must be its leaf-steps to a log: too high and a locked plot's tree sheds its crown,
    // too low and it never decays once felled.
    @Test
    void treeLeavesCarryTheirRealDistanceFromALog() {
        List<Overgrowth.Voxel> voxels = Overgrowth.plan(11, SIZE, GROUND, SETTINGS).voxels();
        Map<Long, Overgrowth.Voxel> at = new HashMap<>();
        for (Overgrowth.Voxel voxel : voxels) {
            at.put(Overgrowth.key(voxel.x(), voxel.y(), voxel.z()), voxel);
        }
        int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        int leaves = 0;
        for (Overgrowth.Voxel voxel : voxels) {
            if (voxel.form() != Overgrowth.Form.LEAVES) {
                continue;
            }
            leaves++;
            assertTrue(voxel.distance() >= 1 && voxel.distance() <= 6, "distance " + voxel);
            int nearest = Integer.MAX_VALUE;
            for (int[] step : steps) {
                Overgrowth.Voxel next = at.get(Overgrowth.key(voxel.x() + step[0], voxel.y() + step[1],
                        voxel.z() + step[2]));
                if (next == null) {
                    continue;
                }
                if (next.form() == Overgrowth.Form.LOG_Y) {
                    nearest = 0;
                } else if (next.form() == Overgrowth.Form.LEAVES) {
                    nearest = Math.min(nearest, next.distance());
                }
            }
            assertEquals(nearest + 1, voxel.distance(), "leaf " + voxel);
        }
        assertTrue(leaves > 0, "no trees were planted");
    }

    @Test
    void nothingIsPlannedWhenThereIsNothingToPlant() {
        assertTrue(Overgrowth.plan(3, SIZE, GROUND, Overgrowth.Settings.OFF).voxels().isEmpty());
    }

    // A config.yml without clearing: must read the shipped defaults, not plant nothing.
    @Test       // block lists are left out: checking one is a block needs a running server's registry
    void anOldConfigReadsTheShippedAmounts() throws Exception {
        YamlConfiguration shipped = new YamlConfiguration();
        shipped.loadFromString("""
                clearing:
                  required-percent: 90
                  trees: { count: 14, types: [oak] }
                  boulders: { count: 10 }
                  undergrowth: { chance: 0.06 }
                """);
        YamlConfiguration onDisk = new YamlConfiguration();
        onDisk.setDefaults(shipped);

        Overgrowth.Settings read = Overgrowth.Settings.read(onDisk.getConfigurationSection("clearing"),
                Logger.getAnonymousLogger());

        assertTrue(read.enabled());
        assertEquals(90, read.required());
        assertEquals(14, read.trees());
        assertEquals(10, read.boulders());
        assertEquals(0.06, read.coverChance(), 1e-9);
        assertFalse(Overgrowth.plan(5, SIZE, GROUND, read).voxels().isEmpty());
    }
}
