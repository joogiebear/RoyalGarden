package com.mystipixel.royalgarden.level;

import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * Garden Level for {@code %royalgarden_<key>%}, answered without PlaceholderAPI's types so the logic
 * loads on any server; {@link com.mystipixel.royalgarden.hooks.RoyalGardenExpansion} is the front end.
 *
 * <pre>
 *   %royalgarden_level%        the player's Garden Level
 *   %royalgarden_xp%           their Garden XP
 *   %royalgarden_max_level%    the highest level levels.yml defines
 * </pre>
 *
 * <p>Plain numbers, so gear can scale with them (a fortune bonus per level) and conditions can compare
 * them. Offline players read {@code 0}: their Garden state is cached for good once read, so a
 * leaderboard or hologram would otherwise load every Garden into memory.
 */
public final class LevelPlaceholders {

    private final GardenLevels levels;

    public LevelPlaceholders(GardenLevels levels) {
        this.levels = levels;
    }

    /** The value for {@code key}, or null if the key is not one of ours. */
    public String resolve(OfflinePlayer player, String key) {
        String params = key.toLowerCase(Locale.ROOT);
        switch (params) {
            case "level":
                return player.isOnline() ? String.valueOf(levels.level(player.getUniqueId())) : "0";
            case "xp":
                return player.isOnline() ? String.valueOf(levels.xp(player.getUniqueId())) : "0";
            case "max_level":
                return String.valueOf(levels.config().maxLevel());
            default:
                return null;
        }
    }
}
