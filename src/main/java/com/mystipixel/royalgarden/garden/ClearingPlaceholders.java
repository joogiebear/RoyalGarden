package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.Messages;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

/**
 * Plot clearing for {@code %royalgarden_<key>%}, for tab lists, scoreboards and the like.
 *
 * <pre>
 *   %royalgarden_clearing_plot%       the plot you are standing on, blank off the board
 *   %royalgarden_clearing_percent%    how clear that plot is: its % while being cleared, 100 once clear,
 *                                     blank when it is locked or you are not in a Garden
 *   %royalgarden_clearing_bar%        a progress bar for it, toward the required %
 *   %royalgarden_clearing_status%     a ready-made line (clearing-status in messages.yml), blank unless
 *                                     you are standing on a plot being cleared
 *   %royalgarden_clearing_required%   the % a plot needs before it can be built on
 *   %royalgarden_clearing_active%     how many of your plots are being cleared
 *   %royalgarden_clearing_&lt;plot&gt;%     one of your plots by name, e.g. clearing_b2, wherever you are
 * </pre>
 *
 * <p>"The plot you are standing on" is in whichever Garden you are in, yours or one you are visiting.
 * Answered for online players only: reading an offline player's Garden would cache it for good.
 */
public final class ClearingPlaceholders {

    private final GardenWorlds worlds;
    private final ClearingService clearing;
    private final Messages messages;

    public ClearingPlaceholders(GardenWorlds worlds, ClearingService clearing, Messages messages) {
        this.worlds = worlds;
        this.clearing = clearing;
        this.messages = messages;
    }

    /** The value for {@code key}, or null if the key is not one of ours. */
    public String resolve(OfflinePlayer offline, String key) {
        String params = key.toLowerCase(Locale.ROOT);
        if (!params.startsWith("clearing_")) {
            return null;
        }
        if (params.equals("clearing_required")) {
            return String.valueOf(clearing.required());
        }
        Player player = offline.getPlayer();
        if (player == null) {
            return "";
        }
        switch (params) {
            case "clearing_active": {
                GardenState own = worlds.state(player.getUniqueId());
                int active = 0;
                for (int[] plot : own.plots()) {
                    if (clearing.blocks(own, plot[0], plot[1])) {
                        active++;
                    }
                }
                return String.valueOf(active);
            }
            case "clearing_plot", "clearing_percent", "clearing_bar", "clearing_status":
                return here(player, params);
            default:
                break;
        }
        int[] plot = GardenLayout.parsePlot(params.substring("clearing_".length()).toUpperCase(Locale.ROOT));
        if (plot == null) {
            return null;
        }
        return percent(worlds.state(player.getUniqueId()), plot[0], plot[1]);
    }

    /** One of the placeholders about the plot the player is standing on. */
    private String here(Player player, String params) {
        GardenState state = worlds.stateOf(player.getWorld());
        int px = GardenLayout.plotAt(player.getLocation().getBlockX());
        int pz = GardenLayout.plotAt(player.getLocation().getBlockZ());
        if (state == null || !GardenLayout.inGrid(px, pz)) {
            return "";
        }
        return switch (params) {
            case "clearing_plot" -> GardenLayout.plotName(px, pz);
            case "clearing_percent" -> percent(state, px, pz);
            case "clearing_bar" -> state.isUnlocked(px, pz) ? value(state, px, pz, "%bar%") : "";
            default -> clearing.blocks(state, px, pz)
                    ? messages.raw("clearing-status", clearing.placeholders(state, px, pz))
                    : "";
        };
    }

    /** A plot's clearing %, 100 once it is clear, blank while it is locked. */
    private String percent(GardenState state, int px, int pz) {
        return state.isUnlocked(px, pz) ? String.valueOf(state.clearingPercent(px, pz)) : "";
    }

    private String value(GardenState state, int px, int pz, String name) {
        String[] pairs = clearing.placeholders(state, px, pz);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            if (pairs[i].equals(name)) {
                return pairs[i + 1];
            }
        }
        return "";
    }
}
