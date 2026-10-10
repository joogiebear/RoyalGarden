package com.mystipixel.royalgarden.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiFunction;

/**
 * PlaceholderAPI front end for {@code %royalgarden_<key>%}. Kept thin because it cannot load without
 * PlaceholderAPI; only construct it once PlaceholderAPI is enabled.
 */
public final class RoyalGardenExpansion extends PlaceholderExpansion {

    private final String version;
    private final BiFunction<OfflinePlayer, String, String> resolver;

    public RoyalGardenExpansion(String version, BiFunction<OfflinePlayer, String, String> resolver) {
        this.version = version;
        this.resolver = resolver;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "royalgarden";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mystipixel";
    }

    @Override
    public @NotNull String getVersion() {
        return version;
    }

    @Override
    public boolean persist() {
        return true;          // survive PlaceholderAPI reloads; the extension unregisters it on disable
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        return player == null ? null : resolver.apply(player, params);
    }
}
