package com.mystipixel.royalgarden.garden;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Which of a player's twenty-five plots are unlocked.
 *
 * <p>The centre plot is unlocked by definition — it carries the farmhouse, and a Garden with no
 * standing room is not a state worth being able to represent. Everything else is bought.
 *
 * <p>Plots are held as a packed {@code pz * GRID + px} so the set is a set of small ints rather than
 * of objects with equality to get wrong. The packing never leaves this class.
 */
public final class GardenState {

    private final UUID owner;
    private final Set<Integer> unlocked = new LinkedHashSet<>();

    public GardenState(UUID owner) {
        this.owner = owner;
        unlocked.add(key(GardenLayout.CENTRE, GardenLayout.CENTRE));
    }

    public UUID owner() {
        return owner;
    }

    private static int key(int px, int pz) {
        return pz * GardenLayout.GRID + px;
    }

    public boolean isUnlocked(int px, int pz) {
        return GardenLayout.inGrid(px, pz) && unlocked.contains(key(px, pz));
    }

    /** How many plots are unlocked, farmhouse included. */
    public int unlockedCount() {
        return unlocked.size();
    }

    /**
     * Whether this plot is the next-thing-you-could-buy: locked, on the board, and orthogonally
     * touching something you already own.
     *
     * <p>Orthogonally, not diagonally: a Garden should grow as one connected piece, and a diagonal-only
     * link would leave a plot you can see and cannot walk to.
     */
    public boolean canUnlock(int px, int pz) {
        if (!GardenLayout.inGrid(px, pz) || isUnlocked(px, pz)) {
            return false;
        }
        return isUnlocked(px - 1, pz) || isUnlocked(px + 1, pz)
                || isUnlocked(px, pz - 1) || isUnlocked(px, pz + 1);
    }

    /** Unlock a plot. Returns false if it was not a legal next purchase. */
    public boolean unlock(int px, int pz) {
        if (!canUnlock(px, pz)) {
            return false;
        }
        return unlocked.add(key(px, pz));
    }

    /**
     * How far out from the farmhouse your unlocks reach, measured in plots.
     *
     * <p>Chebyshev distance — the side length of the smallest square of plots, centred on the
     * farmhouse, that contains everything you own. 0 with only the farmhouse, 2 with a full board.
     *
     * <p>This exists because a Minecraft world border is a <em>centred square</em> and nothing else. It
     * cannot describe an L-shape, so the border is sized to this instead. The consequence is honest and
     * worth knowing: buying one plot in a new ring opens the border across that whole ring. You still
     * only own what you bought — protection is per plot — but you can walk the ring you have reached
     * into.
     */
    public int ring() {
        int max = 0;
        for (int[] plot : plots()) {
            max = Math.max(max, Math.max(Math.abs(plot[0] - GardenLayout.CENTRE),
                    Math.abs(plot[1] - GardenLayout.CENTRE)));
        }
        return max;
    }

    /**
     * The smallest plot-aligned box containing everything you own, as
     * {@code {minPx, minPz, maxPx, maxPz}}.
     *
     * <p>Used to place the border. A world border is a square and cannot be an L-shape, so it is drawn
     * around this box — which hugs the direction your Garden actually grew, rather than assuming it
     * grew evenly in all four.
     */
    public int[] bounds() {
        int minX = GardenLayout.GRID;
        int minZ = GardenLayout.GRID;
        int maxX = -1;
        int maxZ = -1;
        for (int[] plot : plots()) {
            minX = Math.min(minX, plot[0]);
            maxX = Math.max(maxX, plot[0]);
            minZ = Math.min(minZ, plot[1]);
            maxZ = Math.max(maxZ, plot[1]);
        }
        return new int[]{minX, minZ, maxX, maxZ};
    }

    /** Every unlocked plot as {@code {px, pz}} pairs, in unlock order. */
    public Iterable<int[]> plots() {
        Set<int[]> out = new LinkedHashSet<>();
        for (int k : unlocked) {
            out.add(new int[]{k % GardenLayout.GRID, k / GardenLayout.GRID});
        }
        return Collections.unmodifiableSet(out);
    }

    /** Restore a plot from storage, bypassing the adjacency rule. */
    void restore(int px, int pz) {
        if (GardenLayout.inGrid(px, pz)) {
            unlocked.add(key(px, pz));
        }
    }
}
