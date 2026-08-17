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
