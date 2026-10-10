package com.mystipixel.royalgarden.milestone;

import com.mystipixel.royalgarden.config.YamlFile;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@code milestones.yml}. Read without the shipped file as defaults (its {@code crops:} is a list, and a
 * deleted entry would keep coming back), so every setting has its fallback in code.
 */
public final class MilestoneConfig extends YamlFile {

    public enum Counting { BLOCKS, DROPS }

    private final Map<String, MilestoneCrop> crops = new LinkedHashMap<>();
    private final Map<Material, MilestoneCrop> byBlock = new EnumMap<>(Material.class);

    public MilestoneConfig(File dataFolder, Logger logger) {
        super(dataFolder, "milestones.yml", logger, false);
        load();
    }

    @Override
    public void reload() {
        super.reload();
        load();
    }

    private void load() {
        if (crops == null) {
            return;                        // called from the super constructor, before fields exist
        }
        crops.clear();
        byBlock.clear();
        ConfigurationSection root = yaml().getConfigurationSection("crops");
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection c = root.getConfigurationSection(id);
            if (c == null) {
                continue;
            }
            String key = id.toLowerCase(Locale.ROOT);
            Set<Material> blocks = materials(c.getStringList("blocks"), "crops." + id + ".blocks");
            if (blocks.isEmpty()) {
                logger.warning("milestones.yml: crops." + id + " has no valid blocks; skipped.");
                continue;
            }
            long[] tiers = tiers(c.getConfigurationSection("tiers"));
            if (tiers.length == 0) {
                tiers = tiers(yaml().getConfigurationSection("default-tiers"));
            }
            if (tiers.length == 0) {
                logger.warning("milestones.yml: crops." + id + " has no tiers; skipped.");
                continue;
            }
            Material icon = Material.matchMaterial(c.getString("icon", ""));
            MilestoneCrop crop = new MilestoneCrop(key,
                    c.getString("display-name", id),
                    icon == null || !icon.isItem() ? Material.WHEAT : icon,
                    blocks,
                    materials(c.getStringList("drops"), "crops." + id + ".drops"),
                    Math.max(0, c.getInt("points", 1)),
                    c.getBoolean("require-ripe", true),
                    c.getBoolean("natural-only", true),
                    c.getBoolean("stacked", false),
                    tiers);
            crops.put(key, crop);
            for (Material block : blocks) {
                byBlock.put(block, crop);
            }
        }
    }

    // From amounts: [...], or from first / growth / count / round-to.
    private static long[] tiers(ConfigurationSection section) {
        if (section == null) {
            return new long[0];
        }
        List<?> raw = section.getList("amounts");
        if (raw != null && !raw.isEmpty()) {
            List<Number> amounts = new ArrayList<>();
            for (Object value : raw) {
                if (value instanceof Number number) {
                    amounts.add(number);
                }
            }
            return Tiers.explicit(amounts);
        }
        return Tiers.curve(section.getLong("first", 30), section.getDouble("growth", 1.5),
                section.getInt("count", 25), section.getLong("round-to", 10));
    }

    private Set<Material> materials(List<String> names, String where) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        for (String name : names) {
            Material material = Material.matchMaterial(name.trim());
            if (material == null) {
                logger.warning("milestones.yml: " + where + ": '" + name + "' is not a material.");
            } else {
                out.add(material);
            }
        }
        return out;
    }

    public boolean enabled() {
        return yaml().getBoolean("enabled", true);
    }

    public Counting counting() {
        String raw = yaml().getString("counting", "blocks");
        return "drops".equalsIgnoreCase(raw) ? Counting.DROPS : Counting.BLOCKS;
    }

    /** Count only in Garden worlds (true), or in the worlds listed under {@code worlds} (false). */
    public boolean gardensOnly() {
        return yaml().getBoolean("gardens-only", true);
    }

    public List<String> worlds() {
        return yaml().getStringList("worlds");
    }

    public boolean actionBarProgress() {
        return yaml().getBoolean("action-bar-progress", true);
    }

    /** Announce tier-ups to the whole server, not just the player. */
    public boolean broadcastTierUps() {
        return yaml().getBoolean("broadcast-tier-ups", false);
    }

    public int saveIntervalSeconds() {
        return Math.max(5, yaml().getInt("save-interval-seconds", 60));
    }

    public ConfigurationSection rewards() {
        return yaml().getConfigurationSection("rewards");
    }

    public ConfigurationSection menu() {
        return yaml().getConfigurationSection("menu");
    }

    public String tierUpSound() {
        return yaml().getString("tier-up-sound", "entity.player.levelup");
    }

    public MilestoneCrop crop(String id) {
        return id == null ? null : crops.get(id.toLowerCase(Locale.ROOT));
    }

    public MilestoneCrop byBlock(Material block) {
        return byBlock.get(block);
    }

    public Collection<MilestoneCrop> crops() {
        return Collections.unmodifiableCollection(crops.values());
    }

    public boolean tracksPlacement(Material block) {
        MilestoneCrop crop = byBlock.get(block);
        return crop != null && crop.naturalOnly();
    }

    public boolean counts(org.bukkit.World world) {
        if (gardensOnly()) {
            return com.mystipixel.royalgarden.garden.GardenWorlds.isGarden(world);
        }
        String name = world.getName();
        for (String pattern : worlds()) {
            String regex = "(?i)" + java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            if (name.matches(regex)) {
                return true;
            }
        }
        return false;
    }
}
