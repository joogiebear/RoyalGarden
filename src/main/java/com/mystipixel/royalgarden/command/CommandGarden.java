package com.mystipixel.royalgarden.command;

import com.mystipixel.royalgarden.garden.GardenBuilder;
import com.mystipixel.royalgarden.garden.GardenWorlds;
import com.mystipixel.royalgarden.garden.Sam;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.command.impl.PluginCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;

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

    @Override
    public void onExecute(Player player, List<String> args) {
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
