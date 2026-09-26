package com.mystipixel.royalgarden.composter;

import com.mystipixel.royalgarden.composter.ComposterConfig.Upgrade;
import com.mystipixel.royalgarden.config.ItemSpec;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import com.willfp.eco.core.gui.slot.SlotBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The Composter page: organic matter and fuel with their bars (left-click puts one stack in, right-click
 * everything that counts), the status and the next Compost, a collect button, and one button per
 * upgrade. Every icon, line and position comes from {@code menu:} and {@code upgrades:} in
 * {@code composter.yml}. The Composter is caught up each time the page is opened or clicked.
 */
public final class ComposterMenu {

    private final ComposterService composter;
    private final Messages messages;
    private final Consumer<Player> back;

    public ComposterMenu(ComposterService composter, Messages messages, Consumer<Player> back) {
        this.composter = composter;
        this.messages = messages;
        this.back = back;
    }

    public void open(Player player) {
        ComposterConfig config = composter.config();
        if (!config.enabled()) {
            messages.send(player, "composter-disabled");
            return;
        }
        if (composter.store().readOnly(player.getUniqueId())) {
            messages.send(player, "composter-unreadable");
            return;
        }
        composter.update(player.getUniqueId());
        ConfigurationSection menu = config.menu();
        int rows = Math.clamp(menu == null ? 6 : menu.getInt("rows", 6), 3, 6);
        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(
                menu == null ? "&8Composter" : menu.getString("title", "&8Composter")));
        boolean[][] used = new boolean[rows + 1][10];

        place(builder, used, rows, section(menu, "matter"), 2, 3, Material.WHEAT,
                viewer -> composter.placeholders(viewer.getUniqueId()),
                slot -> slot
                        .onLeftClick((event, s, clicked) -> act(event.getWhoClicked(), clicked,
                                who -> composter.insert(who, ComposterService.Kind.MATTER, false)))
                        .onRightClick((event, s, clicked) -> act(event.getWhoClicked(), clicked,
                                who -> composter.insert(who, ComposterService.Kind.MATTER, true))));
        place(builder, used, rows, section(menu, "fuel"), 2, 7, Material.COAL,
                viewer -> composter.placeholders(viewer.getUniqueId()),
                slot -> slot
                        .onLeftClick((event, s, clicked) -> act(event.getWhoClicked(), clicked,
                                who -> composter.insert(who, ComposterService.Kind.FUEL, false)))
                        .onRightClick((event, s, clicked) -> act(event.getWhoClicked(), clicked,
                                who -> composter.insert(who, ComposterService.Kind.FUEL, true))));
        place(builder, used, rows, section(menu, "status"), 2, 5, Material.COMPOSTER,
                viewer -> composter.placeholders(viewer.getUniqueId()),
                slot -> slot.onLeftClick((event, s, clicked) -> act(event.getWhoClicked(), clicked, who -> { })));
        place(builder, used, rows, section(menu, "collect"), 3, 5, Material.BONE_MEAL,
                viewer -> composter.placeholders(viewer.getUniqueId()),
                slot -> slot.onLeftClick((event, s, clicked) -> act(event.getWhoClicked(), clicked, composter::collect)));

        int column = 3;
        for (Upgrade upgrade : Upgrade.values()) {
            int defaultColumn = column++;
            if (!config.upgradeEnabled(upgrade)) {
                continue;
            }
            ConfigurationSection icon = config.upgradeIcon(upgrade, false);
            int row = Math.clamp(icon == null ? rows - 1 : icon.getInt("row", rows - 1), 1, rows);
            int col = Math.clamp(icon == null ? defaultColumn : icon.getInt("column", defaultColumn), 1, 9);
            if (used[row][col]) {
                continue;
            }
            builder.setSlot(row, col, Slot.builder((viewer, current) -> upgradeIcon(viewer, upgrade))
                    .onLeftClick((event, s, clicked) -> act(event.getWhoClicked(), clicked,
                            who -> composter.buy(who, upgrade)))
                    .build());
            used[row][col] = true;
        }

        if (back != null) {
            ConfigurationSection backItem = section(menu, "back");
            int col = Math.clamp(backItem == null ? 5 : backItem.getInt("column", 5), 1, 9);
            int row = Math.clamp(backItem == null ? rows : backItem.getInt("row", rows), 1, rows);
            if (!used[row][col]) {
                builder.setSlot(row, col, Slot.builder(ItemSpec.build(backItem, Material.ARROW))
                        .onLeftClick((event, s, clicked) -> back.accept((Player) event.getWhoClicked()))
                        .build());
                used[row][col] = true;
            }
        }
        String filler = menu == null ? "" : menu.getString("filler", "");
        Material material = filler == null || filler.isBlank() ? null : Material.matchMaterial(filler.trim());
        if (material != null && material.isItem()) {
            ItemStack pane = ItemSpec.plain(material, " ", List.of());
            for (int row = 1; row <= rows; row++) {
                for (int col = 1; col <= 9; col++) {
                    if (!used[row][col]) {
                        builder.setSlot(row, col, Slot.builder(pane).build());
                    }
                }
            }
        }
        builder.build().open(player);
    }

    /** Catch up, do the click, and redraw. */
    private void act(org.bukkit.entity.HumanEntity clicker, Menu clicked, Consumer<Player> action) {
        Player who = (Player) clicker;
        composter.update(who.getUniqueId());
        action.accept(who);
        clicked.refresh(who);
    }

    /** One configured icon, drawn per viewer, at its {@code row} and {@code column}. */
    private static void place(MenuBuilder builder, boolean[][] used, int rows, ConfigurationSection section,
                              int defaultRow, int defaultColumn, Material fallback,
                              Function<Player, String[]> placeholders, Function<SlotBuilder, SlotBuilder> clicks) {
        int row = Math.clamp(section == null ? defaultRow : section.getInt("row", defaultRow), 1, rows);
        int col = Math.clamp(section == null ? defaultColumn : section.getInt("column", defaultColumn), 1, 9);
        if (used[row][col]) {
            return;
        }
        SlotBuilder slot = Slot.builder((viewer, current) -> ItemSpec.build(section, fallback,
                placeholders.apply(viewer)));
        builder.setSlot(row, col, clicks.apply(slot).build());
        used[row][col] = true;
    }

    private ItemStack upgradeIcon(Player player, Upgrade upgrade) {
        ComposterStore.Data data = composter.store().get(player.getUniqueId());
        boolean maxed = composter.nextCost(data, upgrade) == null;
        return ItemSpec.build(composter.config().upgradeIcon(upgrade, maxed), Material.ANVIL,
                composter.upgradePlaceholders(player.getUniqueId(), upgrade));
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        return parent == null ? null : parent.getConfigurationSection(key);
    }
}
