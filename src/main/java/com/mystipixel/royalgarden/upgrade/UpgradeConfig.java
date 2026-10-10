package com.mystipixel.royalgarden.upgrade;

import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.YamlFile;
import com.mystipixel.royalskyblock.currency.Cost;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * {@code upgrades.yml}. Upgradable crops are the milestone crops unless {@code crops.<id>.enabled} says
 * otherwise. Read without the shipped file as defaults, so every setting has its fallback in code.
 */
public final class UpgradeConfig extends YamlFile {

    private static final List<String> DEFAULT_COSTS = List.of("5 copper", "10 copper", "20 copper",
            "50 copper", "100 copper", "250 copper", "500 copper", "1000 copper", "2000 copper");

    public UpgradeConfig(File dataFolder, Logger logger) {
        super(dataFolder, "upgrades.yml", logger, false);
    }

    public boolean enabled() {
        return yaml().getBoolean("enabled", true);
    }

    private ConfigurationSection crop(String crop) {
        return yaml().getConfigurationSection("crops." + crop.toLowerCase(Locale.ROOT));
    }

    public boolean upgradable(String crop) {
        ConfigurationSection own = crop(crop);
        return own == null || own.getBoolean("enabled", true);
    }

    /** {@code costs()[0]} buys level 1; the length is the top level. */
    public List<Cost> costs(String crop) {
        ConfigurationSection own = crop(crop);
        List<String> raw = own != null && own.isList("costs") ? own.getStringList("costs")
                : yaml().isList("costs") ? yaml().getStringList("costs") : DEFAULT_COSTS;
        List<Cost> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            out.add(GardenConfig.parseCost(raw.get(i), "upgrades.yml costs[" + i + "]", logger));
        }
        return out;
    }

    public int maxLevel(String crop) {
        return costs(crop).size();
    }

    /** The crop's own template if it has one, else the shared one. */
    public List<?> effects(String crop) {
        ConfigurationSection own = crop(crop);
        return own != null && own.isList("effects") ? own.getList("effects") : yaml().getList("effects", List.of());
    }

    /** The crop's own template if it has one, else the shared one. */
    public List<?> conditions(String crop) {
        ConfigurationSection own = crop(crop);
        return own != null && own.isList("conditions") ? own.getList("conditions")
                : yaml().getList("conditions", List.of());
    }

    public enum Scope { OWN_GARDEN, GARDENS, EVERYWHERE }

    public Scope applyIn() {
        String raw = yaml().getString("apply-in", "own-garden").trim().toLowerCase(Locale.ROOT);
        return switch (raw) {
            case "gardens" -> Scope.GARDENS;
            case "everywhere" -> Scope.EVERYWHERE;
            default -> Scope.OWN_GARDEN;
        };
    }

    /**
     * Display only: menus show {@code level * value-per-level}. What a level does is its effects; this is
     * the admin's description of them (5 = "+5% double drops").
     */
    public double valuePerLevel(String crop) {
        ConfigurationSection own = crop(crop);
        double fallback = yaml().getDouble("value-per-level", 5);
        return own == null ? fallback : own.getDouble("value-per-level", fallback);
    }

    /** {@code base + per-level * level} */
    public long gardenXp(int level) {
        return Math.max(0, yaml().getLong("garden-xp.base", 5) + yaml().getLong("garden-xp.per-level", 5) * level);
    }

    public String upgradeSound() {
        return yaml().getString("upgrade-sound", "block.anvil.use");
    }

    public ConfigurationSection rewards() {
        return yaml().getConfigurationSection("rewards");
    }

    public ConfigurationSection menu() {
        return yaml().getConfigurationSection("menu");
    }
}
