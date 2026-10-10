package com.mystipixel.royalgarden.milestone;

import com.mystipixel.royalgarden.config.ItemSpec;
import com.mystipixel.royalgarden.config.Messages;
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
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The Crop Milestones page of the desk, for one player's progress (the viewer's own, or another
 * player's when an admin looks them up). Every line comes from menu: in milestones.yml.
 */
public final class MilestoneMenu {

    private final MilestoneService milestones;
    private final Consumer<Player> back;

    public MilestoneMenu(MilestoneService milestones, Consumer<Player> back) {
        this.milestones = milestones;
        this.back = back;
    }

    public void open(Player viewer, UUID owner, String ownerName) {
        MilestoneConfig config = milestones.config();
        ConfigurationSection menu = config.menu();
        List<MilestoneCrop> crops = new ArrayList<>(config.crops());
        int shown = Math.min(45, crops.size());
        int rows = Math.max(2, Math.min(6, (shown + 8) / 9 + 1));

        MenuBuilder builder = Menu.builder(rows).setTitle(Text.legacy(Messages.fill(
                menu == null ? "&8Crop Milestones" : menu.getString("title", "&8Crop Milestones"),
                "%player%", ownerName)));

        for (int i = 0; i < shown; i++) {
            MilestoneCrop crop = crops.get(i);
            builder.setSlot(i / 9 + 1, i % 9 + 1, Slot.builder(icon(menu, crop, owner)).build());
        }
        if (back != null) {
            ConfigurationSection backItem = menu == null ? null : menu.getConfigurationSection("back");
            builder.setSlot(rows, 5, Slot.builder(ItemSpec.build(backItem, Material.ARROW))
                    .onLeftClick((event, slot, clicked) -> back.accept((Player) event.getWhoClicked()))
                    .build());
        }
        builder.build().open(viewer);
    }

    private ItemStack icon(ConfigurationSection menu, MilestoneCrop crop, UUID owner) {
        long total = milestones.store().total(owner, crop.id());
        int tier = crop.tierOf(total);
        String[] placeholders = milestones.placeholders(crop, tier, total);
        ConfigurationSection entry = menu == null ? null
                : menu.getConfigurationSection(tier >= crop.maxTier() ? "entry-maxed" : "entry");
        if (entry == null && menu != null) {
            entry = menu.getConfigurationSection("entry");
        }
        String name = entry == null ? "&6%crop_name% %tier_roman%" : entry.getString("name", "&6%crop_name% %tier_roman%");
        List<String> lore = new ArrayList<>();
        List<String> lines = entry == null
                ? List.of("&7Total: &e%total%", "&7Next tier: &e%next%", "%bar% &7%percent%%")
                : entry.getStringList("lore");
        for (String line : lines) {
            lore.add(Messages.fill(line, placeholders));
        }
        return ItemSpec.plain(crop.icon(), Messages.fill(name, placeholders), lore);
    }
}
