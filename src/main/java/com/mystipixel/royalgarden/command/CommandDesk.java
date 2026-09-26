package com.mystipixel.royalgarden.command;

import com.mystipixel.royalgarden.garden.GardenMenu;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.command.impl.PluginCommand;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * {@code /desk} — the farmhouse desk, without walking to it.
 *
 * <p>The same thing right-clicking Sam opens. Hypixel's Garden works this way round too: the desk is
 * the interface, the NPC is one door into it, and the command is the door you use once you know where
 * you are going. The name comes from {@code commands.desk} in the config.
 */
public final class CommandDesk extends PluginCommand {

    /** The node this command needs. See {@link CommandGarden#PERMISSION} for why it is not blank. */
    public static final String PERMISSION = "royalgarden.desk";

    private final GardenMenu menu;

    public CommandDesk(EcoPlugin plugin, String name, GardenMenu menu) {
        super(plugin, name, PERMISSION, true);
        this.menu = menu;
    }

    @Override
    public void onExecute(Player player, List<String> args) {
        menu.open(player);
    }
}
