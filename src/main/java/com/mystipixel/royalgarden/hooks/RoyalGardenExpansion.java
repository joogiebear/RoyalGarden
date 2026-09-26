package com.mystipixel.royalgarden.hooks;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.function.BiFunction;

/**
 * PlaceholderAPI front end for {@code %royalgarden_<key>%}.
 *
 * <p>Deliberately thin, like the host's own expansion: it extends a PlaceholderAPI type and so cannot
 * even load without it, which is why the answers come from a resolver that knows nothing about
 * PlaceholderAPI. Only constructed once PlaceholderAPI is known to be enabled. The class resolves because
 * RoyalSkyblock soft-depends on PlaceholderAPI, and an extension's classes load through the host's.
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
