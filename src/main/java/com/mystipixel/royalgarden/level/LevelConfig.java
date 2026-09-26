package com.mystipixel.royalgarden.level;

import com.mystipixel.royalgarden.config.YamlFile;
import com.mystipixel.royalgarden.milestone.Tiers;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * {@code levels.yml}: how much Garden XP each level takes, where XP comes from, and what levels pay.
 *
 * <p>Levels start at 1. {@code thresholds()[0]} is the XP total that reaches level 2, and the last
 * entry reaches the top level — so there is always one threshold fewer than there are levels.
 */
public final class LevelConfig extends YamlFile {

    private long[] thresholds = new long[0];

    public LevelConfig(File dataFolder, Logger logger) {
        super(dataFolder, "levels.yml", logger, false);
        load();
    }

    @Override
    public void reload() {
        super.reload();
        load();
    }

    private void load() {
        ConfigurationSection xp = yaml().getConfigurationSection("xp");
        List<?> raw = xp == null ? null : xp.getList("amounts");
        if (raw != null && !raw.isEmpty()) {
            List<Number> amounts = new ArrayList<>();
            for (Object value : raw) {
                if (value instanceof Number number) {
                    amounts.add(number);
                }
            }
            thresholds = Tiers.explicit(amounts);
        } else {
            thresholds = Tiers.curve(xp == null ? 70 : xp.getLong("first", 70),
                    xp == null ? 1.6 : xp.getDouble("growth", 1.6),
                    Math.max(0, yaml().getInt("max-level", 15) - 1),
                    xp == null ? 10 : xp.getLong("round-to", 10));
        }
    }

    public boolean enabled() {
        return yaml().getBoolean("enabled", true);
    }

    public long[] thresholds() {
        return thresholds;
    }

    public int maxLevel() {
        return thresholds.length + 1;
    }

    /** The level a total reaches, from 1 to {@link #maxLevel()}. */
    public int levelOf(long xp) {
        return levelOf(thresholds, xp);
    }

    /** The level {@code xp} reaches against these thresholds: 1, plus one per threshold met. */
    public static int levelOf(long[] thresholds, long xp) {
        int level = 1;
        while (level - 1 < thresholds.length && xp >= thresholds[level - 1]) {
            level++;
        }
        return level;
    }

    /** XP needed for the level after {@code level}, or -1 at the top. */
    public long nextThreshold(int level) {
        return level - 1 < thresholds.length ? thresholds[level - 1] : -1;
    }

    /** XP the given level started at. */
    public long levelStart(int level) {
        return level <= 1 ? 0 : thresholds[level - 2];
    }

    // ── sources ────────────────────────────────────────────────────────────────

    /** XP for reaching a milestone tier: {@code base + per-tier × tier}. */
    public long milestoneXp(int tier) {
        return Math.max(0, yaml().getLong("sources.milestone-tier.base", 10)
                + yaml().getLong("sources.milestone-tier.per-tier", 2) * tier);
    }

    /** XP for buying the {@code purchase}-th plot: {@code base + per-plot × purchase}. */
    public long unlockXp(int purchase) {
        return Math.max(0, yaml().getLong("sources.plot-unlock.base", 25)
                + yaml().getLong("sources.plot-unlock.per-plot", 5) * purchase);
    }

    // ── display ────────────────────────────────────────────────────────────────

    public boolean broadcastLevelUps() {
        return yaml().getBoolean("broadcast-level-ups", true);
    }

    public boolean announceXpGains() {
        return yaml().getBoolean("announce-xp-gains", true);
    }

    public String levelUpSound() {
        return yaml().getString("level-up-sound", "ui.toast.challenge_complete");
    }

    public ConfigurationSection progressBar() {
        return yaml().getConfigurationSection("progress-bar");
    }

    public ConfigurationSection rewards() {
        return yaml().getConfigurationSection("rewards");
    }
}
