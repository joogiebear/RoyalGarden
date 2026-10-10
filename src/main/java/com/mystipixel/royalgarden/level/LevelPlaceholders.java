package com.mystipixel.royalgarden.level;

import org.bukkit.OfflinePlayer;

import java.util.Locale;

/**
 * Garden Level for {@code %royalgarden_<key>%}, without PlaceholderAPI types.
 *
 * <pre>
 *   %royalgarden_level%        the player's Garden Level
 *   %royalgarden_xp%           their Garden XP
 *   %royalgarden_max_level%    the highest level levels.yml defines
 * </pre>
 *
 * <p>Offline players read {@code 0}: Garden state stays cached once read, so a leaderboard would
 * otherwise load every Garden into memory.
 */
public final class LevelPlaceholders {

    private final GardenLevels levels;

    public LevelPlaceholders(GardenLevels levels) {
        this.levels = levels;
    }

    /** Null if the key is not one of ours. */
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
