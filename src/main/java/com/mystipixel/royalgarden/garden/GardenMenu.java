package com.mystipixel.royalgarden.garden;

import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The desk menu: the board, drawn as itself.
 *
 * <p>A 5&times;5 grid of plots and a 9&times;6 inventory are a lucky fit — the board sits in columns
 * 3&ndash;7 of the first five rows, dead centre, and every slot is the plot it represents rather than
 * a list entry describing one. Clicking a plot you own walks you to it; clicking one you can buy buys
 * it.
 *
 * <p>Built once and rendered per player: every slot reads the clicking player's own unlock state, so
 * one menu object serves everybody and {@link Menu#refresh} is enough to show an unlock the instant it
 * happens.
 */
public final class GardenMenu {

    /** Left-hand column of the board inside the inventory, so the five plots land centred. */
    private static final int COLUMN_OFFSET = 3;

    private final GardenWorlds worlds;
    private final Menu menu;

    public GardenMenu(GardenWorlds worlds) {
        this.worlds = worlds;
        this.menu = build();
    }

    public void open(Player player) {
        menu.open(player);
    }

    private Menu build() {
        MenuBuilder builder = Menu.builder(6).setTitle("The Garden");

        for (int px = 0; px < GardenLayout.GRID; px++) {
            for (int pz = 0; pz < GardenLayout.GRID; pz++) {
                int plotX = px;
                int plotZ = pz;
                // eco slots are 1-indexed (row, column). North is up: plot z maps to the row.
                builder.setSlot(pz + 1, px + COLUMN_OFFSET, Slot.builder(
                                player -> icon(player, plotX, plotZ))
                        .onLeftClick((event, slot, clicked) ->
                                click((Player) event.getWhoClicked(), clicked, plotX, plotZ))
                        .build());
            }
        }

        builder.setSlot(6, 5, Slot.builder(this::summary).build());
        return builder.build();
    }

    // ── rendering ──────────────────────────────────────────────────────────────

    /** The item for one plot, from this player's point of view. */
    private ItemStack icon(Player player, int px, int pz) {
        GardenState state = worlds.state(player.getUniqueId());
        String name = GardenLayout.plotName(px, pz);

        if (GardenLayout.isCentre(px, pz)) {
            return item(Material.HAY_BLOCK,
                    Component.text("Farmhouse", NamedTextColor.GOLD),
                    List.of(grey("Plot " + name + " — always yours."),
                            grey("Sam and the desk are here."),
                            Component.empty(),
                            grey("This plot is protected.")));
        }
        if (state.isUnlocked(px, pz)) {
            return item(Material.GRASS_BLOCK,
                    Component.text("Plot " + name, NamedTextColor.GREEN),
                    List.of(grey("Unlocked."),
                            Component.empty(),
                            Component.text("Click to go here.", NamedTextColor.YELLOW)));
        }
        if (state.canUnlock(px, pz)) {
            return item(Material.LIME_STAINED_GLASS_PANE,
                    Component.text("Plot " + name, NamedTextColor.YELLOW),
                    List.of(grey("Locked, and next to a plot you own."),
                            Component.empty(),
                            Component.text("Click to unlock.", NamedTextColor.YELLOW)));
        }
        return item(Material.GRAY_STAINED_GLASS_PANE,
                Component.text("Plot " + name, NamedTextColor.DARK_GRAY),
                List.of(grey("Locked — you cannot farm here."),
                        grey("Unlock a plot next to it first.")));
    }

    /** The counter at the bottom of the menu. */
    private ItemStack summary(Player player) {
        GardenState state = worlds.state(player.getUniqueId());
        int total = GardenLayout.GRID * GardenLayout.GRID;
        return item(Material.WHEAT,
                Component.text("Your Garden", NamedTextColor.GREEN),
                List.of(grey(state.unlockedCount() + " of " + total + " plots unlocked"),
                        grey("Each plot is " + GardenLayout.PLOT_SIZE + "×" + GardenLayout.PLOT_SIZE),
                        Component.empty(),
                        grey("The border sits at the edge of what"),
                        grey("you have reached, and moves out as"),
                        grey("you unlock further from the farmhouse.")));
    }

    // ── clicking ───────────────────────────────────────────────────────────────

    private void click(Player player, Menu clicked, int px, int pz) {
        GardenState state = worlds.state(player.getUniqueId());

        if (state.isUnlocked(px, pz)) {
            visit(player, px, pz);
            return;
        }
        if (!state.canUnlock(px, pz)) {
            player.sendMessage(Component.text("Unlock a plot next to that one first.", NamedTextColor.RED));
            return;
        }
        if (worlds.unlock(player.getUniqueId(), px, pz)) {
            player.sendMessage(Component.text("Unlocked plot " + GardenLayout.plotName(px, pz) + ".",
                    NamedTextColor.GREEN));
            clicked.refresh(player);
        }
    }

    /** Walk the player to a plot, opening their Garden first if they are not standing in it. */
    private void visit(Player player, int px, int pz) {
        World here = player.getWorld();
        if (GardenWorlds.isGarden(here) && player.getUniqueId().equals(GardenWorlds.ownerOf(here))) {
            player.closeInventory();
            player.teleportAsync(GardenLayout.plotCentre(here, px, pz));
            return;
        }
        player.closeInventory();
        worlds.open(player.getUniqueId()).thenAccept(world ->
                player.teleportAsync(GardenLayout.plotCentre(world, px, pz)));
    }

    // ── item helpers ───────────────────────────────────────────────────────────

    private static ItemStack item(Material material, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        // Item names and lore render italic by default, which reads as placeholder text.
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>(lore.size());
        for (Component line : lore) {
            lines.add(line.decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Component grey(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }
}
