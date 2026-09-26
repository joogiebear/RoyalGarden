package com.mystipixel.royalgarden.garden;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything remembered about one player's Garden besides its blocks.
 *
 * <ul>
 *   <li><b>Unlocked plots.</b> The centre plot is unlocked by definition — it carries the farmhouse,
 *       and a Garden with no standing room is not a state worth being able to represent.</li>
 *   <li><b>What each plot was last built from</b>, so the desk can show it.</li>
 *   <li><b>When each plot was last rebuilt</b>, for the preset cooldown.</li>
 *   <li><b>Rebuilds still in progress</b>, so one interrupted by the world unloading picks up again
 *       on the next visit instead of leaving half a farm.</li>
 * </ul>
 *
 * <p>Plots are held as a packed {@code pz * grid + px} so the sets are sets of small ints rather than
 * of objects with equality to get wrong. The packing never leaves this class.
 */
public final class GardenState {

    private final UUID owner;
    private final Set<Integer> unlocked = new LinkedHashSet<>();
    private final Map<Integer, String> presets = new HashMap<>();
    private final Map<Integer, Long> lastApplied = new HashMap<>();
    private final Map<Integer, String> pending = new HashMap<>();
    private boolean legacyWallsCleared;
    private boolean readOnly;

    public GardenState(UUID owner) {
        this.owner = owner;
        unlocked.add(key(GardenLayout.centre(), GardenLayout.centre()));
    }

    public UUID owner() {
        return owner;
    }

    private static int key(int px, int pz) {
        return pz * GardenLayout.grid() + px;
    }

    private static int[] unkey(int k) {
        return new int[]{k % GardenLayout.grid(), k / GardenLayout.grid()};
    }

    public boolean isUnlocked(int px, int pz) {
        return GardenLayout.inGrid(px, pz) && unlocked.contains(key(px, pz));
    }

    /** How many plots are unlocked, farmhouse included. */
    public int unlockedCount() {
        return unlocked.size();
    }

    /** How many plots have been bought — the farmhouse is free, so it does not count. */
    public int purchasedCount() {
        return unlocked.size() - 1;
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
        if (readOnly || !canUnlock(px, pz)) {
            return false;
        }
        return unlocked.add(key(px, pz));
    }

    /** Unlock a plot regardless of adjacency — for admins, and for reading from storage. */
    public boolean forceUnlock(int px, int pz) {
        return GardenLayout.inGrid(px, pz) && unlocked.add(key(px, pz));
    }

    /** Lock a plot again. The farmhouse cannot be locked. */
    public boolean lock(int px, int pz) {
        if (!GardenLayout.inGrid(px, pz) || GardenLayout.isCentre(px, pz)) {
            return false;
        }
        return unlocked.remove(key(px, pz));
    }

    /** Every unlocked plot as {@code {px, pz}} pairs, in unlock order. */
    public List<int[]> plots() {
        List<int[]> out = new ArrayList<>(unlocked.size());
        for (int k : unlocked) {
            out.add(unkey(k));
        }
        return Collections.unmodifiableList(out);
    }

    // ── presets on plots ───────────────────────────────────────────────────────

    /** The preset a plot was last built from, or null if it still has what it was generated with. */
    public String presetOn(int px, int pz) {
        return presets.get(key(px, pz));
    }

    public void setPresetOn(int px, int pz, String id) {
        if (id == null) {
            presets.remove(key(px, pz));
        } else {
            presets.put(key(px, pz), id);
        }
    }

    /** When a plot was last rebuilt, in epoch milliseconds; 0 if never. */
    public long lastApplied(int px, int pz) {
        return lastApplied.getOrDefault(key(px, pz), 0L);
    }

    public void setLastApplied(int px, int pz, long when) {
        lastApplied.put(key(px, pz), when);
    }

    /** Record a rebuild that has started and not yet finished. */
    public void setPending(int px, int pz, String id) {
        if (id == null) {
            pending.remove(key(px, pz));
        } else {
            pending.put(key(px, pz), id);
        }
    }

    /** Rebuilds that were started and never finished, as {@code plot -> preset id}. */
    public Map<int[], String> pending() {
        Map<int[], String> out = new HashMap<>();
        pending.forEach((k, id) -> out.put(unkey(k), id));
        return out;
    }

    public boolean legacyWallsCleared() {
        return legacyWallsCleared;
    }

    public void setLegacyWallsCleared(boolean cleared) {
        this.legacyWallsCleared = cleared;
    }

    /**
     * Whether this state could not be read from disk and must not be written back.
     *
     * <p>Saving a state that failed to load would replace whatever is on disk — possibly a player's
     * whole Garden behind one bad character — with an empty one. So a Garden in this state refuses
     * changes until an admin resets it or fixes the file.
     */
    public boolean readOnly() {
        return readOnly;
    }

    void markReadOnly() {
        this.readOnly = true;
    }

    // ── storage views ──────────────────────────────────────────────────────────

    Map<Integer, String> presetsByKey() {
        return presets;
    }

    Map<Integer, Long> appliedByKey() {
        return lastApplied;
    }

    Map<Integer, String> pendingByKey() {
        return pending;
    }

    static int keyOf(int px, int pz) {
        return key(px, pz);
    }

    static int[] plotOf(int k) {
        return unkey(k);
    }
}
