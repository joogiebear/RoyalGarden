package com.mystipixel.royalgarden.upgrade;

import com.mystipixel.royalgarden.config.ItemSpec;
import com.mystipixel.royalgarden.config.Messages;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
import com.mystipixel.royalskyblock.util.Text;
import com.willfp.eco.core.gui.menu.Menu;
import com.willfp.eco.core.gui.menu.MenuBuilder;
import com.willfp.eco.core.gui.slot.Slot;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** The Crop Upgrades page of the desk. Every line comes from menu: in upgrades.yml. */
public final class UpgradeMenu {

    private final UpgradeService upgrades;
    private final Messages messages;
    private final Consumer<Player> back;

    public UpgradeMenu(UpgradeService upgrades, Messages messages, Consumer<Player> back) {
        this.upgrades = upgrades;
        this.messages = messages;
        this.back = back;
    }

    public void open(Player player) {
        if (!upgrades.config().enabled()) {
            messages.send(player, "upgrades-disabled");
            return;
        }
        ConfigurationSection menu = upgrades.config().menu();
        List<MilestoneCrop> crops = new ArrayList<>(upgrades.crops());
        int shown = Math.min(45, crops.size());
        int rows = Math.max(2, Math.min(6, (shown + 8) / 9 + 1));
        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(
                menu.getString("title")));
        boolean[][] used = new boolean[rows + 1][10];

        for (int i = 0; i < shown; i++) {
            MilestoneCrop crop = crops.get(i);
            int row = i / 9 + 1;
            int column = i % 9 + 1;
            builder.setSlot(row, column, Slot.builder((viewer, current) -> icon(viewer, crop))
                    .onLeftClick((event, slot, clicked) -> {
                        Player who = (Player) event.getWhoClicked();
                        upgrades.buy(who, crop);
                        clicked.refresh(who);
                    })
                    .build());
            used[row][column] = true;
        }
        if (back != null) {
            ConfigurationSection backItem = menu.getConfigurationSection("back");
            int column = Math.clamp(backItem == null ? 5 : backItem.getInt("column", 5), 1, 9);
            builder.setSlot(rows, column, Slot.builder(ItemSpec.build(backItem, Material.ARROW))
                    .onLeftClick((event, slot, clicked) -> back.accept((Player) event.getWhoClicked()))
                    .build());
            used[rows][column] = true;
        }
        String filler = menu.getString("filler");
        Material material = filler == null || filler.isBlank() ? null : Material.matchMaterial(filler.trim());
        if (material != null && material.isItem()) {
            ItemStack pane = ItemSpec.plain(material, " ", List.of());
            for (int row = 1; row <= rows; row++) {
                for (int column = 1; column <= 9; column++) {
                    if (!used[row][column]) {
                        builder.setSlot(row, column, Slot.builder(pane).build());
                    }
                }
            }
        }
        builder.build().open(player);
    }

    private ItemStack icon(Player player, MilestoneCrop crop) {
        ConfigurationSection menu = upgrades.config().menu();
        boolean maxed = upgrades.nextCost(player.getUniqueId(), crop) == null;
        ConfigurationSection entry = menu.getConfigurationSection(maxed ? "entry-maxed" : "entry");
        String[] placeholders = upgrades.placeholders(player.getUniqueId(), crop);
        String name = entry.getString("name");
        List<String> lines = entry.getStringList("lore");
        List<String> lore = new ArrayList<>();
        for (String line : lines) {
            String filled = Messages.fill(line, placeholders);
            if (line.trim().startsWith("%") && line.trim().endsWith("%") && filled.isBlank()) {
                continue;
            }
            for (String part : filled.split("\n", -1)) {
                lore.add(part);
            }
        }
        return ItemSpec.plain(crop.icon(), Messages.fill(name, placeholders), lore);
    }
}
