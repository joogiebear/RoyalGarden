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
 * Everything remembered about one player's Garden besides its blocks: unlocked plots (the centre always),
 * each plot's last preset and rebuild time, unfinished rebuilds, and {@link Overgrowth} debris and
 * clearing progress. Plots are packed as {@code pz * grid + px}; the packing never leaves this class.
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

    /** Farmhouse included. */
    public int unlockedCount() {
        return unlocked.size();
    }

    /** The farmhouse is free, so it does not count. */
    public int purchasedCount() {
        return unlocked.size() - 1;
    }

    /**
     * Locked, on the board, and orthogonally touching an owned plot. Not diagonally: a diagonal-only link
     * would leave a plot you can see and cannot walk to.
     */
    public boolean canUnlock(int px, int pz) {
        if (!GardenLayout.inGrid(px, pz) || isUnlocked(px, pz)) {
            return false;
        }
        return isUnlocked(px - 1, pz) || isUnlocked(px + 1, pz)
                || isUnlocked(px, pz - 1) || isUnlocked(px, pz + 1);
    }

    /**
     * Returns false if it was not a legal next purchase. A plot bought with debris starts being cleared
     * (see {@link #beginClearing}).
     */
    public boolean unlock(int px, int pz) {
        if (readOnly || !canUnlock(px, pz)) {
            return false;
        }
        unlocked.add(key(px, pz));
        beginClearing(px, pz);
        return true;
    }

    /** Ignores adjacency: for admins, and for reading from storage. */
    public boolean forceUnlock(int px, int pz) {
        return GardenLayout.inGrid(px, pz) && unlocked.add(key(px, pz));
    }

    /**
     * The farmhouse cannot be locked. A plot locked part-way through clearing keeps its remaining debris as
     * its overgrowth.
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

    /** As {@code {px, pz}} pairs, in unlock order. */
    public List<int[]> plots() {
        List<int[]> out = new ArrayList<>(unlocked.size());
        for (int k : unlocked) {
            out.add(unkey(k));
        }
        return Collections.unmodifiableList(out);
    }

    /** Null if it still has what it was generated with. */
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

    /** Epoch milliseconds; 0 if never. */
    public long lastApplied(int px, int pz) {
        return lastApplied.getOrDefault(key(px, pz), 0L);
    }

    public void setLastApplied(int px, int pz, long when) {
        lastApplied.put(key(px, pz), when);
    }

    /** Records a rebuild that has started and not yet finished. */
    public void setPending(int px, int pz, String id) {
        if (id == null) {
            pending.remove(key(px, pz));
        } else {
            pending.put(key(px, pz), id);
        }
    }

    /** As {@code plot -> preset id}. */
    public Map<int[], String> pending() {
        Map<int[], String> out = new HashMap<>();
        pending.forEach((k, id) -> out.put(unkey(k), id));
        return out;
    }

    /** Whether a locked plot has had its debris generated. */
    public boolean isOvergrown(int px, int pz) {
        return overgrowth.containsKey(key(px, pz));
    }

    /** If the plot was bought while its debris was still being laid, clearing starts now instead. */
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
     * Returns true if the plot now needs clearing. A plot with no debris is ready to build on at once.
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

    /** Below or above the threshold. */
    public boolean isClearing(int px, int pz) {
        return clearing.containsKey(key(px, pz));
    }

    /** 0 if it is not being cleared. */
    public int clearingTotal(int px, int pz) {
        int[] progress = clearing.get(key(px, pz));
        return progress == null ? 0 : progress[0];
    }

    /** 0 if it is not being cleared. */
    public int clearingDone(int px, int pz) {
        int[] progress = clearing.get(key(px, pz));
        return progress == null ? 0 : progress[1];
    }

    /** Held between none and all of it. */
    public void setClearingDone(int px, int pz, int done) {
        int[] progress = clearing.get(key(px, pz));
        if (progress != null) {
            progress[1] = Math.clamp(done, 0, progress[0]);
        }
    }

    /** 0-100, rounded down; 100 when it is not being cleared. */
    public int clearingPercent(int px, int pz) {
        int[] progress = clearing.get(key(px, pz));
        return progress == null ? 100 : percent(progress[1], progress[0]);
    }

    /** Rounded down so 89.9% never reads as 90. */
    public static int percent(int done, int total) {
        return total <= 0 ? 100 : (int) Math.min(100, (long) done * 100 / total);
    }

    /** Returns false if it was not being cleared. */
    public boolean finishClearing(int px, int pz) {
        return clearing.remove(key(px, pz)) != null;
    }

    /** For a plot that has just been rebuilt over, locked or bought. */
    public void forgetDebris(int px, int pz) {
        overgrowth.remove(key(px, pz));
        clearing.remove(key(px, pz));
    }

    /** The level is worked out from it; it is never spent. */
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
     * Which {@link Farmhouse#id} the farmhouse plot was built with. Older Gardens have none and read as the
     * built-in barn at their {@link #barnVersion}.
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
     * True if this state could not be read from disk. Saving it would overwrite the file with an empty
     * Garden, so it refuses changes until an admin resets it or fixes the file.
     */
    public boolean readOnly() {
        return readOnly;
    }

    void markReadOnly() {
        this.readOnly = true;
    }

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
