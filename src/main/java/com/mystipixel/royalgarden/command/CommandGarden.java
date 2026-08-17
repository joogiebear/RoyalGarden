package com.mystipixel.royalgarden.command;

import com.mystipixel.royalgarden.garden.GardenBuilder;
import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.garden.PresetLibrary;
import com.mystipixel.royalgarden.garden.Sam;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.command.impl.PluginCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * {@code /garden} — go to your Garden, building it if you have never been.
 *
 * <p>Registered through eco's command framework rather than a {@code plugin.yml} entry, because an
 * extension has no {@code plugin.yml} of its own to put one in. eco registers into the live command
 * map, which is what makes commands possible from an extension at all.
 */
public final class CommandGarden extends PluginCommand {

    /**
     * The node this command needs.
     *
     * <p>Not the empty string. eco checks {@code sender.hasPermission(getPermission())} with no
     * special case for a blank one, and Bukkit resolves an <em>unregistered</em> permission — which
     * {@code ""} is — through {@code PermissionDefault.OP}. An empty permission therefore reads as
     * "operators only", silently, which is the exact opposite of what it looks like it means. The node
     * is registered with {@code default: true} on enable, matching the host's own player-facing nodes.
     */
    public static final String PERMISSION = "royalgarden.garden";

    private final GardenWorlds worlds;
    private final Sam sam;

    public CommandGarden(EcoPlugin plugin, GardenWorlds worlds, Sam sam) {
        super(plugin, "garden", PERMISSION, true);
        this.worlds = worlds;
        this.sam = sam;
    }

    /** Lets a player capture and stamp plot templates. */
    public static final String ADMIN = "royalgarden.admin";

    @Override
    public void onExecute(Player player, List<String> args) {
        if (!args.isEmpty()) {
            admin(player, args);
            return;
        }
        visit(player);
    }

    /**
     * The template subcommands: capture a plot, stamp it over the others, or forget it.
     *
     * <p>Kept as arguments on {@code /garden} rather than a command of their own — three admin verbs
     * do not need their own name in the command map, and grouping them means the permission that gates
     * them is one node instead of three.
     */
    private void admin(Player player, List<String> args) {
        if (!player.hasPermission(ADMIN)) {
            player.sendMessage(Component.text("You cannot do that.", NamedTextColor.RED));
            return;
        }
        World world = player.getWorld();
        if (!GardenWorlds.isGarden(world)) {
            player.sendMessage(Component.text("Stand in a Garden first.", NamedTextColor.RED));
            return;
        }

        String verb = args.get(0).toLowerCase(Locale.ROOT);
        String name = args.size() > 1 ? args.get(1).toLowerCase(Locale.ROOT) : null;
        PresetLibrary presets = worlds.presets();

        switch (verb) {
            case "capture" -> capture(player, world, name);
            case "apply" -> apply(player, world, name);
            case "flat" -> apply(player, world, null);
            case "default" -> {
                if (name != null && !presets.exists(name)) {
                    player.sendMessage(Component.text("No preset called '" + name + "'.", NamedTextColor.RED));
                    return;
                }
                presets.setDefault(name);
                player.sendMessage(Component.text(name == null
                        ? "New plots will be flat."
                        : "New plots will be built from '" + name + "'.", NamedTextColor.GREEN));
            }
            case "delete" -> player.sendMessage(presets.delete(name)
                    ? Component.text("Deleted preset '" + name + "'.", NamedTextColor.GREEN)
                    : Component.text("No preset called '" + name + "'.", NamedTextColor.RED));
            case "presets" -> list(player, presets);
            default -> player.sendMessage(Component.text(
                    "/garden — go to your Garden. Admin: capture <name>, apply <name>, flat, "
                            + "default [name], delete <name>, presets.", NamedTextColor.GRAY));
        }
    }

    private void list(Player player, PresetLibrary presets) {
        List<String> ids = presets.ids();
        if (ids.isEmpty()) {
            player.sendMessage(Component.text("No presets captured yet.", NamedTextColor.GRAY));
            return;
        }
        player.sendMessage(Component.text("Presets:", NamedTextColor.GREEN));
        for (String id : ids) {
            boolean isDefault = id.equals(presets.defaultId());
            player.sendMessage(Component.text("  " + id, NamedTextColor.WHITE)
                    .append(isDefault
                            ? Component.text("  (default)", NamedTextColor.YELLOW)
                            : Component.empty()));
        }
    }

    /** Capture the plot the player is standing on under a name. */
    private void capture(Player player, World world, String name) {
        if (name == null || !PresetLibrary.validId(name)) {
            player.sendMessage(Component.text(
                    "Name the preset: /garden capture <name>  (a-z, 0-9, _ and - only)",
                    NamedTextColor.RED));
            return;
        }
        int px = GardenLayout.plotAt(player.getLocation().getBlockX());
        int pz = GardenLayout.plotAt(player.getLocation().getBlockZ());
        if (!GardenLayout.inGrid(px, pz)) {
            player.sendMessage(Component.text("Stand on a plot to capture it.", NamedTextColor.RED));
            return;
        }
        if (GardenLayout.isCentre(px, pz)) {
            // The farmhouse plot is never stamped, so capturing it could only ever be a mistake.
            player.sendMessage(Component.text(
                    "That is the farmhouse plot. Build presets on any other plot.", NamedTextColor.RED));
            return;
        }
        try {
            int layers = worlds.capture(world, px, pz, name).height();
            player.sendMessage(Component.text("Captured plot " + GardenLayout.plotName(px, pz)
                    + " as '" + name + "' (" + layers + " layers).", NamedTextColor.GREEN));
        } catch (IOException failed) {
            player.sendMessage(Component.text("Could not save the preset: " + failed.getMessage(),
                    NamedTextColor.RED));
            getPlugin().getLogger().severe("Preset capture failed: " + failed);
        }
    }

    /** Stamp a preset onto the plot the player is standing on, or flatten it. */
    private void apply(Player player, World world, String name) {
        int px = GardenLayout.plotAt(player.getLocation().getBlockX());
        int pz = GardenLayout.plotAt(player.getLocation().getBlockZ());
        if (!GardenLayout.inGrid(px, pz)) {
            player.sendMessage(Component.text("Stand on the plot you want to change.", NamedTextColor.RED));
            return;
        }
        if (GardenLayout.isCentre(px, pz)) {
            player.sendMessage(Component.text("The farmhouse plot cannot be replaced.", NamedTextColor.RED));
            return;
        }
        if (name != null && !worlds.presets().exists(name)) {
            player.sendMessage(Component.text("No preset called '" + name + "'.", NamedTextColor.RED));
            return;
        }
        worlds.applyPreset(world, px, pz, name);
        player.sendMessage(Component.text(name == null
                ? "Flattening plot " + GardenLayout.plotName(px, pz) + "…"
                : "Applying '" + name + "' to plot " + GardenLayout.plotName(px, pz) + "…",
                NamedTextColor.GREEN));
    }

    private void visit(Player player) {
        boolean firstVisit = !worlds.isLoaded(player.getUniqueId());
        if (firstVisit) {
            player.sendMessage(Component.text("Opening your Garden…", NamedTextColor.GRAY));
        }

        worlds.open(player.getUniqueId()).whenComplete((world, error) -> {
            if (error != null) {
                // The future can fail on either thread; get back on the main one before touching the
                // player, and log the whole chain — the message alone is usually the least useful part.
                Bukkit.getScheduler().runTask(getPlugin(), () ->
                        player.sendMessage(Component.text("Your Garden could not be opened. "
                                + "The error is in the server log.", NamedTextColor.RED)));
                getPlugin().getLogger().severe("Failed to open Garden for " + player.getName() + ":");
                error.printStackTrace();
                return;
            }
            arrive(player, world);
        });
    }

    /** Put the player on the doorstep, and make sure Sam is at the desk before they walk in. */
    private void arrive(Player player, World world) {
        // open() already completes on the server thread, but arriving is worth being explicit about:
        // both calls below are main-thread-only, and a future's thread is easy to lose track of.
        Bukkit.getScheduler().runTask(getPlugin(), () -> {
            sam.ensure(world);
            player.teleportAsync(GardenBuilder.spawn(world)).thenAccept(moved -> {
                if (moved) {
                    player.sendMessage(Component.text("Welcome to your Garden. ", NamedTextColor.GREEN)
                            .append(Component.text("Talk to Sam, or use /desk.", NamedTextColor.GRAY)));
                }
            });
        });
    }
}
