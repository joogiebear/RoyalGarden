package com.mystipixel.royalgarden.garden;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The desk in the farmhouse — what {@code /desk} opens and what Sam hands you.
 *
 * <p>Phase 1 is a status readout, not a menu. Every line of it reports something the extension had to
 * get right to print at all: that you are standing in a Garden world, which of the twenty-five plots
 * you are on, and where the board's edges are. It is a working instrument for checking the layout,
 * and it is what the plot-unlock menu will replace once there is unlock state worth showing.
 */
public final class Desk {

    private Desk() {
    }

    /** Show the desk to a player. Safe to call from anywhere; it only reads and sends. */
    public static void open(Player player) {
        Location at = player.getLocation();
        boolean inGarden = GardenWorlds.isGarden(at.getWorld());

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("The Garden", NamedTextColor.GREEN)
                .decorate(TextDecoration.BOLD)
                .append(Component.text("  ·  Desk", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.BOLD, false)));

        if (!inGarden) {
            player.sendMessage(Component.text("You are not in your Garden. ", NamedTextColor.GRAY)
                    .append(Component.text("/garden", NamedTextColor.YELLOW))
                    .append(Component.text(" takes you there.", NamedTextColor.GRAY)));
            player.sendMessage(Component.empty());
            return;
        }

        int px = GardenLayout.plotAt(at.getBlockX());
        int pz = GardenLayout.plotAt(at.getBlockZ());
        boolean onBoard = GardenLayout.inGrid(px, pz);

        player.sendMessage(line("Standing on", onBoard
                ? "plot " + GardenLayout.plotName(px, pz)
                + (GardenLayout.isCentre(px, pz) ? " — the farmhouse" : "")
                : "off the board"));
        player.sendMessage(line("Board", GardenLayout.GRID + "×" + GardenLayout.GRID
                + " plots of " + GardenLayout.PLOT_SIZE + "×" + GardenLayout.PLOT_SIZE
                + " (" + GardenLayout.SPAN + " blocks square)"));
        player.sendMessage(line("Unlocked", "1 of " + (GardenLayout.GRID * GardenLayout.GRID)
                + " — the farmhouse plot"));

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Plot unlocking is not built yet; the other 24 plots are ",
                        NamedTextColor.DARK_GRAY)
                .append(Component.text("empty sky", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.ITALIC))
                .append(Component.text(" until they are bought.", NamedTextColor.DARK_GRAY)));
        player.sendMessage(Component.empty());
    }

    private static Component line(String label, String value) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }
}
