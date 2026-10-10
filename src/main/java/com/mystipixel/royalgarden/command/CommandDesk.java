package com.mystipixel.royalgarden.command;

import com.mystipixel.royalgarden.garden.GardenMenu;
import com.willfp.eco.core.EcoPlugin;
import com.willfp.eco.core.command.impl.PluginCommand;
import org.bukkit.entity.Player;

import java.util.List;

/** /desk: opens the farmhouse desk, the same menu right-clicking Sam opens. Name from commands.desk. */
public final class CommandDesk extends PluginCommand {

    /** See CommandGarden.PERMISSION for why this is not blank. */
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
