package com.mystipixel.royalgarden.config;

import com.mystipixel.royalskyblock.util.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * A menu icon described in config:
 *
 * <pre>
 *   material: grass_block
 *   name: "&amp;aPlot %plot%"
 *   lore:
 *     - "&amp;7Left-click to go here."
 * </pre>
 *
 * <p>A lore line that is exactly a placeholder which fills to nothing is dropped, so an optional line
 * leaves no gap.
 */
public final class ItemSpec {

    private ItemSpec() {
    }

    /** A stone placeholder named after the path if {@code section} is missing. */
    public static ItemStack build(ConfigurationSection section, Material fallback, String... placeholders) {
        if (section == null) {
            return plain(fallback, "&c(missing menu item)", List.of());
        }
        Material material = fallback;
        String raw = section.getString("material");
        if (raw != null && !raw.isBlank()) {
            Material found = Material.matchMaterial(raw.trim());
            if (found != null && found.isItem()) {
                material = found;
            }
        }
        String name = Messages.fill(section.getString("name", ""), placeholders);
        List<String> lore = new ArrayList<>();
        for (String line : section.getStringList("lore")) {
            boolean optional = line.trim().startsWith("%") && line.trim().endsWith("%");
            String filled = Messages.fill(line, placeholders);
            if (optional && filled.isBlank()) {
                continue;
            }
            // A placeholder may carry several lines of its own, separated by newlines.
            for (String part : filled.split("\n", -1)) {
                lore.add(part);
            }
        }
        return plain(material, name, lore);
    }

    /** Coloured name and lore, not italicised. */
    public static ItemStack plain(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        // Item names and lore render italic by default, which reads as placeholder text.
        meta.displayName(Text.color(name).decoration(TextDecoration.ITALIC, false));
        List<Component> lines = new ArrayList<>(lore.size());
        for (String line : lore) {
            lines.add(Text.color(line).decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }
}
