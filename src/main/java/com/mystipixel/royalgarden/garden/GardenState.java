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
 *   <li><b>Debris</b>: how much {@link Overgrowth} each locked plot was generated with, and — once a
 *       plot is bought — how much of it has been cleared. A plot being cleared cannot be built on.</li>
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
    private final Map<Integer, Integer> overgrowth = new HashMap<>();
    private final Map<Integer, int[]> clearing = new HashMap<>();
    private boolean legacyWallsCleared;
    private int barnVersion = 1;
    private String farmhouse;
    private long xp;
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

    /**
     * Unlock a plot. Returns false if it was not a legal next purchase.
     *
     * <p>A plot bought with debris on it starts being cleared: see {@link #beginClearing}.
     */
    public boolean unlock(int px, int pz) {
        if (readOnly || !canUnlock(px, pz)) {
            return false;
        }
        unlocked.add(key(px, pz));
        beginClearing(px, pz);
        return true;
    }

    /** Unlock a plot regardless of adjacency — for admins, and for reading from storage. */
    public boolean forceUnlock(int px, int pz) {
        return GardenLayout.inGrid(px, pz) && unlocked.add(key(px, pz));
    }

    /**
     * Lock a plot again. The farmhouse cannot be locked.
     *
     * <p>A plot locked part-way through clearing keeps the debris still on it as its overgrowth, so
     * unlocking it again asks for what is left to be cleared rather than what was there to begin with.
     */
    public boolean lock(int px, int pz) {
        if (!GardenLayout.inGrid(px, pz) || GardenLayout.isCentre(px, pz)) {
            return false;
        }
        if (!unlocked.remove(key(px, pz))) {
            return false;
        }
        int[] progress = clearing.remove(key(px, pz));
        if (progress != null && progress[0] > progress[1]) {
            overgrowth.put(key(px, pz), progress[0] - progress[1]);
        }
        return true;
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

    // ── debris ─────────────────────────────────────────────────────────────────

    /** Whether a locked plot has had its debris generated. */
    public boolean isOvergrown(int px, int pz) {
        return overgrowth.containsKey(key(px, pz));
    }

    /**
     * Record that a plot's debris has been generated, {@code blocks} of it.
     *
     * <p>If the plot was bought while its debris was still being laid, clearing starts now instead.
     */
    public void setOvergrowth(int px, int pz, int blocks) {
        int k = key(px, pz);
        if (unlocked.contains(k)) {
            if (!clearing.containsKey(k) && blocks > 0) {
                clearing.put(k, new int[]{blocks, 0});
            }
            return;
        }
        overgrowth.put(k, Math.max(0, blocks));
    }

    /**
     * Start clearing a plot that has debris on it. Returns true if it now needs clearing.
     *
     * <p>A plot generated with no debris — or bought before debris existed — is ready to build on at once.
     */
    public boolean beginClearing(int px, int pz) {
        int k = key(px, pz);
        Integer blocks = overgrowth.remove(k);
        if (blocks == null || blocks <= 0 || !unlocked.contains(k)) {
            return false;
        }
        clearing.put(k, new int[]{blocks, 0});
        return true;
    }

    /** Whether a plot is bought and still has a clearing record — below or above the threshold. */
    public boolean isClearing(int px, int pz) {
        return clearing.containsKey(key(px, pz));
    }

    /** Debris the plot had when clearing began; 0 if it is not being cleared. */
    public int clearingTotal(int px, int pz) {
        int[] progress = clearing.get(key(px, pz));
        return progress == null ? 0 : progress[0];
    }

    /** Debris cleared from the plot so far; 0 if it is not being cleared. */
    public int clearingDone(int px, int pz) {
        int[] progress = clearing.get(key(px, pz));
        return progress == null ? 0 : progress[1];
    }

    /** Set how much has been cleared, held between none and all of it. */
    public void setClearingDone(int px, int pz, int done) {
        int[] progress = clearing.get(key(px, pz));
        if (progress != null) {
            progress[1] = Math.clamp(done, 0, progress[0]);
        }
    }

    /** How far through clearing a plot is, 0–100, rounded down; 100 when it is not being cleared. */
    public int clearingPercent(int px, int pz) {
        int[] progress = clearing.get(key(px, pz));
        return progress == null ? 100 : percent(progress[1], progress[0]);
    }

    /** {@code done} of {@code total} as a whole percentage, rounded down so 89.9% never reads as 90. */
    public static int percent(int done, int total) {
        return total <= 0 ? 100 : (int) Math.min(100, (long) done * 100 / total);
    }

    /** Stop tracking a plot's clearing: it is ready to build on. Returns false if it was not being cleared. */
    public boolean finishClearing(int px, int pz) {
        return clearing.remove(key(px, pz)) != null;
    }

    /** Forget any debris a plot had, locked or bought — for a plot that has just been rebuilt over. */
    public void forgetDebris(int px, int pz) {
        overgrowth.remove(key(px, pz));
        clearing.remove(key(px, pz));
    }

    /** Garden XP earned in total. The level is worked out from it; it is never spent. */
    public long xp() {
        return xp;
    }

    public void setXp(long xp) {
        this.xp = Math.max(0, xp);
    }

    /** Which {@link GardenBuilder#BARN_VERSION} this Garden's barn was built or last renovated to. */
    public int barnVersion() {
        return barnVersion;
    }

    public void setBarnVersion(int version) {
        this.barnVersion = version;
    }

    /**
     * Which {@link Farmhouse#id} the farmhouse plot was built with. Gardens from before schematics were
     * recorded have none, and read as the built-in barn at their {@link #barnVersion}.
     */
    public String farmhouse() {
        return farmhouse != null ? farmhouse : "generated-" + barnVersion;
    }

    public void setFarmhouse(String id) {
        this.farmhouse = id;
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

    Map<Integer, Integer> overgrowthByKey() {
        return overgrowth;
    }

    Map<Integer, int[]> clearingByKey() {
        return clearing;
    }

    static int keyOf(int px, int pz) {
        return key(px, pz);
    }

    static int[] plotOf(int k) {
        return unkey(k);
    }
}
