package com.mystipixel.royalgarden.milestone;

import org.bukkit.Material;

import java.util.Set;

/**
 * One crop with milestones, from {@code milestones.yml}.
 *
 * @param blocks      the blocks that are this crop when broken
 * @param drops       the items counted in {@code drops} counting
 * @param points      how much one block adds in {@code blocks} counting
 * @param requireRipe only fully grown blocks count, for anything with an age
 * @param naturalOnly blocks a player placed never count
 * @param stacked     the same crop stacked above the broken block falls with it and counts too
 * @param tiers       the running total needed for each tier: {@code tiers[0]} reaches tier 1
 */
public record MilestoneCrop(String id, String displayName, Material icon, Set<Material> blocks,
                            Set<Material> drops, int points, boolean requireRipe, boolean naturalOnly,
                            boolean stacked, long[] tiers) {

    /** The tier a total has reached: 0 before the first threshold, {@code tiers.length} at the top. */
    public int tierOf(long total) {
        int tier = 0;
        while (tier < tiers.length && total >= tiers[tier]) {
            tier++;
        }
        return tier;
    }

    public int maxTier() {
        return tiers.length;
    }

    /** The total needed for the tier after this one, or -1 at the top. */
    public long nextThreshold(int tier) {
        return tier < tiers.length ? tiers[tier] : -1;
    }

    /** The total the current tier started at. */
    public long tierStart(int tier) {
        return tier <= 0 ? 0 : tiers[tier - 1];
    }
}
