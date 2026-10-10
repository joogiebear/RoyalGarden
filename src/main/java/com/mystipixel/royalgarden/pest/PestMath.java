package com.mystipixel.royalgarden.pest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pest arithmetic. Pure, so it can be tested without a server. Plots are packed keys
 * ({@code pz * grid + px}); a cap of zero or less means no cap.
 */
public final class PestMath {

    private PestMath() {
    }

    /** {@code cooldown} milliseconds since the last pest. */
    public static boolean offCooldown(long lastSpawn, long now, long cooldown) {
        return lastSpawn <= 0 || cooldown <= 0 || now - lastSpawn >= cooldown || now < lastSpawn;
    }

    /** {@code roll} uniform in {@code [0, 1)}. */
    public static boolean rolls(double percent, double roll) {
        return percent > 0 && roll * 100.0 < percent;
    }

    /** On a plot holding {@code onPlot}, in a Garden holding {@code inGarden}. */
    public static boolean hasRoom(int onPlot, int inGarden, int perPlot, int perGarden) {
        return (perPlot <= 0 || onPlot < perPlot) && (perGarden <= 0 || inGarden < perGarden);
    }

    /** {@code infestedAt} of zero or less never is. */
    public static boolean infested(int pests, int infestedAt) {
        return infestedAt > 0 && pests >= infestedAt;
    }

    public static int total(Map<Integer, Integer> counts) {
        int total = 0;
        for (int count : counts.values()) {
            total += Math.max(0, count);
        }
        return total;
    }

    /**
     * The plot a new pest lands on, or -1 if none has room: the harvested plot, unless {@code otherChance}
     * percent sends it to another eligible plot. A full harvested plot sends it elsewhere; a full Garden
     * sends it nowhere.
     *
     * @param eligible  every plot a pest may land on (unlocked, and not the farmhouse unless allowed)
     * @param otherRoll uniform in {@code [0, 1)}: whether to pick another plot
     * @param pickRoll  uniform in {@code [0, 1)}: which other plot
     */
    public static int pickPlot(int harvested, List<Integer> eligible, Map<Integer, Integer> counts, int perPlot,
                               int perGarden, double otherChance, double otherRoll, double pickRoll) {
        int inGarden = total(counts);
        if (perGarden > 0 && inGarden >= perGarden) {
            return -1;
        }
        boolean harvestedFits = eligible.contains(harvested)
                && hasRoom(counts.getOrDefault(harvested, 0), inGarden, perPlot, perGarden);
        if (harvestedFits && !rolls(otherChance, otherRoll)) {
            return harvested;
        }
        List<Integer> others = new ArrayList<>();
        for (int plot : eligible) {
            if (plot != harvested && hasRoom(counts.getOrDefault(plot, 0), inGarden, perPlot, perGarden)) {
                others.add(plot);
            }
        }
        if (others.isEmpty()) {
            return harvestedFits ? harvested : -1;
        }
        int index = (int) Math.floor(Math.clamp(pickRoll, 0.0, Math.nextDown(1.0)) * others.size());
        return others.get(index);
    }

    /**
     * A coordinate on one axis of a plot spanning {@code min .. min + size - 1}, at least {@code margin}
     * blocks in from both edges.
     */
    public static int spot(int min, int size, int margin, double roll) {
        int inset = Math.clamp(margin, 0, Math.max(0, (size - 1) / 2));
        int span = size - 2 * inset;
        return min + inset + (int) Math.floor(Math.clamp(roll, 0.0, Math.nextDown(1.0)) * span);
    }

    /** {@code min .. max} inclusive on both axes. */
    public static boolean outside(double x, double z, int minX, int maxX, int minZ, int maxZ) {
        return x < minX || x >= maxX + 1 || z < minZ || z >= maxZ + 1;
    }
}
