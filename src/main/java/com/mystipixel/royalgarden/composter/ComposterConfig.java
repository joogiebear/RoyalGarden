package com.mystipixel.royalgarden.composter;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.YamlFile;
import com.mystipixel.royalgarden.milestone.MilestoneConfig;
import com.mystipixel.royalgarden.milestone.MilestoneCrop;
import com.mystipixel.royalgarden.visitor.VisitorService;
import com.mystipixel.royalskyblock.currency.Cost;
import com.willfp.eco.core.items.TestableItem;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * {@code composter.yml}: what the Composter accepts and what it is worth, how fast it works, what it
 * makes, its upgrades, rewards, the block in the barn and the menu.
 *
 * <p>Organic matter defaults to every crop's drops from {@code milestones.yml}, so a crop is defined
 * once and the Composter follows it; {@code organic-matter.items} adds or overrides any item, EcoItems
 * ids included. Read without the shipped file as defaults, since the item lists and per-crop values are
 * the admin's to delete; every setting has its fallback in code.
 */
public final class ComposterConfig extends YamlFile {

    /** One kind of item the Composter accepts, and what each one is worth. */
    public record Source(String id, TestableItem item, double value) {
    }

    /** The upgrades, fixed in code because each changes a different part of the machine. */
    public enum Upgrade {
        SPEED("speed", 20),
        MULTI_DROP("multi-drop", 3),
        MATTER_CAP("matter-cap", 5_000),
        FUEL_CAP("fuel-cap", 2_000),
        COST_REDUCTION("cost-reduction", 1);

        private final String key;
        private final double defaultPerLevel;

        Upgrade(String key, double defaultPerLevel) {
            this.key = key;
            this.defaultPerLevel = defaultPerLevel;
        }

        public String key() {
            return key;
        }

        public static Upgrade byKey(String key) {
            for (Upgrade upgrade : values()) {
                if (upgrade.key.equalsIgnoreCase(key)) {
                    return upgrade;
                }
            }
            return null;
        }
    }

    private static final List<String> DEFAULT_COSTS = List.of("10 copper", "25 copper", "50 copper",
            "100 copper", "250 copper");

    private final MilestoneConfig crops;
    private List<Source> matter;
    private List<Source> fuel;

    public ComposterConfig(File dataFolder, Logger logger, MilestoneConfig crops) {
        super(dataFolder, "composter.yml", logger, false);
        this.crops = crops;
    }

    @Override
    public void reload() {
        super.reload();
        matter = null;                     // item lookups are rebuilt on next use
        fuel = null;
    }

    public boolean enabled() {
        return yaml().getBoolean("enabled", true);
    }

    // ── what goes in ───────────────────────────────────────────────────────────

    /**
     * Everything that counts as organic matter: {@code organic-matter.items} first, so a custom item
     * is matched before the vanilla crop it is made from, then every crop's drops.
     */
    public List<Source> matterSources() {
        if (matter == null) {
            List<Source> out = new ArrayList<>(parse(yaml().getStringList("organic-matter.items"),
                    "organic-matter.items"));
            if (yaml().getBoolean("organic-matter.from-crops", true)) {
                double fallback = yaml().getDouble("organic-matter.crop-value", 1);
                ConfigurationSection values = yaml().getConfigurationSection("organic-matter.crops");
                for (MilestoneCrop crop : crops.crops()) {
                    double value = values == null ? fallback : values.getDouble(crop.id(), fallback);
                    for (Material drop : crop.drops()) {
                        String id = drop.name().toLowerCase(Locale.ROOT);
                        TestableItem item = VisitorService.lookup(id);
                        if (item != null && value > 0 && !listed(out, id)) {
                            out.add(new Source(id, item, value));
                        }
                    }
                }
            }
            matter = List.copyOf(out);
        }
        return matter;
    }

    /** Everything that counts as fuel, from {@code fuel.items}. */
    public List<Source> fuelSources() {
        if (fuel == null) {
            fuel = List.copyOf(parse(yaml().getStringList("fuel.items"), "fuel.items"));
        }
        return fuel;
    }

    private static boolean listed(List<Source> sources, String id) {
        for (Source source : sources) {
            if (source.id().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** {@code "<item> <value>"} lines, the item being any eco item lookup. */
    private List<Source> parse(List<String> lines, String where) {
        List<Source> out = new ArrayList<>();
        for (String line : lines) {
            String trimmed = line == null ? "" : line.trim();
            int space = trimmed.lastIndexOf(' ');
            if (space <= 0) {
                logger.warning("composter.yml " + where + ": '" + line + "' should be '<item> <value>'.");
                continue;
            }
            String id = trimmed.substring(0, space).trim();
            double value;
            try {
                value = Double.parseDouble(trimmed.substring(space + 1).trim());
            } catch (NumberFormatException notANumber) {
                logger.warning("composter.yml " + where + ": '" + line + "' has no number after the item.");
                continue;
            }
            TestableItem item = VisitorService.lookup(id);
            if (item == null) {
                logger.warning("composter.yml " + where + ": '" + id + "' is not an item on this server.");
                continue;
            }
            if (value > 0) {
                out.add(new Source(id, item, value));
            }
        }
        return out;
    }

    /** The first source an item matches, or null. */
    public static Source match(List<Source> sources, ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return null;
        }
        for (Source source : sources) {
            if (source.item().matches(stack)) {
                return source;
            }
        }
        return null;
    }

    // ── the machine ────────────────────────────────────────────────────────────

    /** Milliseconds per Compost before upgrades. */
    public long baseInterval() {
        long millis = Durations.parseMillis(yaml().getString("compost.time", "10m"));
        return millis > 0 ? millis : 600_000L;
    }

    public double matterPerCompost() {
        return Math.max(0, yaml().getDouble("compost.organic-matter", 160));
    }

    public double fuelPerCompost() {
        return Math.max(0, yaml().getDouble("compost.fuel", 100));
    }

    public double baseMatterCap() {
        return Math.max(0, yaml().getDouble("compost.max-organic-matter", 20_000));
    }

    public double baseFuelCap() {
        return Math.max(0, yaml().getDouble("compost.max-fuel", 10_000));
    }

    /** The most finished Compost a Composter holds; 0 for no limit. */
    public long maxStored() {
        return Math.max(0, yaml().getLong("compost.max-stored", 1_000));
    }

    /** How often online players' Composters are caught up, in ticks. */
    public long checkTicks() {
        return Math.max(20L, yaml().getLong("compost.check-every-seconds", 30) * 20L);
    }

    /** Whether a player is told when their Composter stops. */
    public boolean notifyStopped() {
        return yaml().getBoolean("compost.notify-stopped", true);
    }

    // ── what comes out ─────────────────────────────────────────────────────────

    /** An eco item lookup for Compost, or blank to only run {@link #outputCommands()}. */
    public String outputItem() {
        return yaml().getString("output.item", "bone_meal");
    }

    /** Console commands run on collecting, with {@code %amount%}. */
    public List<String> outputCommands() {
        return yaml().getStringList("output.commands");
    }

    public String outputName() {
        return yaml().getString("output.name", "Compost");
    }

    /** Garden XP per Compost collected. */
    public double gardenXpPerCompost() {
        return Math.max(0, yaml().getDouble("garden-xp.per-compost", 2));
    }

    /** Garden XP for buying upgrade {@code level}: {@code base + per-level × level}. */
    public long upgradeGardenXp(int level) {
        return Math.max(0, yaml().getLong("garden-xp.upgrade.base", 5)
                + yaml().getLong("garden-xp.upgrade.per-level", 5) * level);
    }

    public ConfigurationSection rewards() {
        return yaml().getConfigurationSection("rewards");
    }

    // ── upgrades ───────────────────────────────────────────────────────────────

    private ConfigurationSection upgrade(Upgrade upgrade) {
        return yaml().getConfigurationSection("upgrades." + upgrade.key());
    }

    public boolean upgradeEnabled(Upgrade upgrade) {
        ConfigurationSection own = upgrade(upgrade);
        return own == null || own.getBoolean("enabled", true);
    }

    /** What one level adds: percent for speed, multi-drop and cost-reduction; an amount for the caps. */
    public double perLevel(Upgrade upgrade) {
        ConfigurationSection own = upgrade(upgrade);
        return own == null ? upgrade.defaultPerLevel : own.getDouble("per-level", upgrade.defaultPerLevel);
    }

    /** The cost of each level in order; its length is the top level. */
    public List<Cost> costs(Upgrade upgrade) {
        ConfigurationSection own = upgrade(upgrade);
        List<String> raw = own != null && own.isList("costs") ? own.getStringList("costs") : DEFAULT_COSTS;
        List<Cost> out = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            out.add(GardenConfig.parseCost(raw.get(i), "composter.yml upgrades." + upgrade.key() + ".costs[" + i + "]",
                    logger));
        }
        return out;
    }

    public int maxLevel(Upgrade upgrade) {
        return upgradeEnabled(upgrade) ? costs(upgrade).size() : 0;
    }

    public String upgradeName(Upgrade upgrade) {
        ConfigurationSection own = upgrade(upgrade);
        return own == null ? upgrade.key() : own.getString("name", upgrade.key());
    }

    /** The menu icon for an upgrade: {@code icon-maxed} at the top level if there is one. */
    public ConfigurationSection upgradeIcon(Upgrade upgrade, boolean maxed) {
        ConfigurationSection own = upgrade(upgrade);
        if (own == null) {
            return null;
        }
        ConfigurationSection icon = maxed ? own.getConfigurationSection("icon-maxed") : null;
        return icon != null ? icon : own.getConfigurationSection("icon");
    }

    public String upgradeSound() {
        return yaml().getString("sounds.upgrade", "block.anvil.use");
    }

    public String insertSound() {
        return yaml().getString("sounds.insert", "block.composter.fill");
    }

    public String collectSound() {
        return yaml().getString("sounds.collect", "entity.item.pickup");
    }

    // ── the block ──────────────────────────────────────────────────────────────

    public boolean blockEnabled() {
        return yaml().getBoolean("block.enabled", true);
    }

    public Material blockMaterial() {
        Material material = Material.matchMaterial(yaml().getString("block.material", "composter"));
        return material == null || !material.isBlock() ? Material.COMPOSTER : material;
    }

    /** Whether the block is placed at the spot if something else is there. */
    public boolean placeBlock() {
        return yaml().getBoolean("block.place", true);
    }

    /** x, height above the plot surface, z. */
    public int[] blockSpot() {
        return new int[]{yaml().getInt("block.x", 6), yaml().getInt("block.y", 1), yaml().getInt("block.z", -1)};
    }

    // ── the menu ───────────────────────────────────────────────────────────────

    public ConfigurationSection menu() {
        return yaml().getConfigurationSection("menu");
    }

    public int barLength() {
        return Math.clamp(yaml().getInt("progress-bar.length", 20), 1, 100);
    }

    public String barFilled() {
        return yaml().getString("progress-bar.filled", "&a|");
    }

    public String barEmpty() {
        return yaml().getString("progress-bar.empty", "&7|");
    }
}
