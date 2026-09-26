package com.mystipixel.royalgarden.pest;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.YamlFile;
import com.mystipixel.royalgarden.visitor.VisitorMath;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * {@code pests.yml}: how often pests come and where, what an infested plot does, each kind of pest, what
 * killing one pays, and the Pests page.
 *
 * <p>Read without the shipped file as defaults, since the pests are the admin's to delete; every
 * setting has its fallback in code.
 */
public final class PestConfig extends YamlFile {

    /** Whose farming an infested plot slows down. */
    public enum AppliesTo { OWNER, EVERYONE }

    private Map<String, PestType> pests = Map.of();

    public PestConfig(File dataFolder, Logger logger) {
        super(dataFolder, "pests.yml", logger, false);
    }

    @Override
    public void reload() {
        super.reload();
        Map<String, PestType> out = new LinkedHashMap<>();
        ConfigurationSection section = yaml().getConfigurationSection("pests");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                ConfigurationSection pest = section.getConfigurationSection(id);
                if (pest == null) {
                    continue;
                }
                if (!id.matches("[a-z0-9_]+")) {
                    logger.warning("pests.yml: pest id '" + id + "' must be a-z, 0-9 and _ — skipped.");
                    continue;
                }
                String command = pest.getString("command", "");
                if (command.isBlank()) {
                    logger.warning("pests.yml: pests." + id + " has no command to spawn it — skipped.");
                    continue;
                }
                Material icon = Material.matchMaterial(pest.getString("icon", "spider_eye"));
                out.put(id, new PestType(id, pest.getString("name", id),
                        icon == null || !icon.isItem() ? Material.SPIDER_EYE : icon,
                        command, pest.getString("mob", "").trim().toLowerCase(Locale.ROOT),
                        pest.getDouble("weight", 1), Math.max(1, pest.getInt("garden-level", 1)),
                        range(pest, "copper", id), Math.max(0, pest.getLong("garden-xp", 0)),
                        List.copyOf(pest.getStringList("description")), pest.getConfigurationSection("rewards")));
            }
        }
        this.pests = out;
    }

    private VisitorMath.Range range(ConfigurationSection section, String key, String id) {
        if (!section.contains(key)) {
            return VisitorMath.Range.ZERO;
        }
        VisitorMath.Range range = VisitorMath.Range.parse(section.get(key));
        if (range == null) {
            logger.warning("pests.yml: pests." + id + "." + key + ": '" + section.get(key)
                    + "' is not a number or range like 2-4.");
            return VisitorMath.Range.ZERO;
        }
        return range;
    }

    public boolean enabled() {
        return yaml().getBoolean("enabled", true);
    }

    // ── the pests ──────────────────────────────────────────────────────────────

    public Collection<PestType> pests() {
        return pests.values();
    }

    public PestType pest(String id) {
        return id == null ? null : pests.get(id.toLowerCase(Locale.ROOT));
    }

    /** Kinds of pest a Garden of this level may get. */
    public List<PestType> eligible(int level) {
        List<PestType> out = new ArrayList<>();
        for (PestType type : pests.values()) {
            if (type.gardenLevel() <= level) {
                out.add(type);
            }
        }
        return out;
    }

    // ── spawning ───────────────────────────────────────────────────────────────

    /** Percent chance a counted harvest of this crop brings a pest. */
    public double chance(String crop) {
        ConfigurationSection perCrop = yaml().getConfigurationSection("spawning.crop-chance");
        double fallback = yaml().getDouble("spawning.chance", 1.0);
        return crop != null && perCrop != null ? perCrop.getDouble(crop, fallback) : fallback;
    }

    public long cooldownMillis() {
        return Math.max(0, Durations.parseMillis(yaml().getString("spawning.cooldown", "1m")));
    }

    public int maxPerPlot() {
        return yaml().getInt("spawning.max-per-plot", 3);
    }

    public int maxPerGarden() {
        return yaml().getInt("spawning.max-per-garden", 10);
    }

    public double otherPlotChance() {
        return yaml().getDouble("spawning.other-plot-chance", 20);
    }

    public boolean farmhouse() {
        return yaml().getBoolean("spawning.farmhouse", false);
    }

    public int edgeMargin() {
        return Math.max(0, yaml().getInt("spawning.edge-margin", 4));
    }

    public int attempts() {
        return Math.clamp(yaml().getInt("spawning.attempts", 6), 1, 50);
    }

    // ── infestation ────────────────────────────────────────────────────────────

    public int infestedAt() {
        return yaml().getInt("infested-at", 1);
    }

    public AppliesTo appliesTo() {
        String raw = yaml().getString("infested-applies-to", "owner");
        return "everyone".equalsIgnoreCase(raw == null ? "" : raw.trim()) ? AppliesTo.EVERYONE : AppliesTo.OWNER;
    }

    /** The {@code infested-effects:} template as parsed. */
    public List<?> effects() {
        List<?> list = yaml().getList("infested-effects");
        return list == null ? List.of() : list;
    }

    public List<?> conditions() {
        List<?> list = yaml().getList("infested-conditions");
        return list == null ? List.of() : list;
    }

    /** How often pests are held to their plot, in ticks. */
    public long confineTicks() {
        return Math.max(10L, Math.round(yaml().getDouble("confine.check-every-seconds", 2) * 20));
    }

    // ── killing ────────────────────────────────────────────────────────────────

    public boolean ownerOnly() {
        return yaml().getBoolean("kills.owner-only", true);
    }

    public ConfigurationSection rewards() {
        return yaml().getConfigurationSection("rewards");
    }

    public String spawnSound() {
        return yaml().getString("sounds.spawn", "entity.silverfish.ambient");
    }

    public String killSound() {
        return yaml().getString("sounds.kill", "entity.experience_orb.pickup");
    }

    // ── the menu ───────────────────────────────────────────────────────────────

    public ConfigurationSection menu() {
        return yaml().getConfigurationSection("menu");
    }
}
