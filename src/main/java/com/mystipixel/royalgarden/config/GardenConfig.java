package com.mystipixel.royalgarden.config;

import com.mystipixel.royalgarden.garden.Overgrowth;
import com.mystipixel.royalskyblock.currency.Cost;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * {@code config.yml}. Accessors read the live file, so {@code /garden reload} applies on the next call.
 * The exception is {@code layout:}, read once at startup: reshaping the board under existing Gardens
 * would misplace every plot.
 */
public final class GardenConfig extends YamlFile {

    // Parsed once per load: it is asked for on every block broken in a Garden.
    private Overgrowth.Settings clearing;
    // Also once per load: asked for on every liquid flow, piston and creature spawn.
    private volatile Set<SpawnReason> spawnReasons;
    private volatile Map<String, Boolean> protections = new ConcurrentHashMap<>();

    public GardenConfig(File dataFolder, Logger logger) {
        super(dataFolder, "config.yml", logger);
    }

    @Override
    public void reload() {
        super.reload();
        clearing = null;
        spawnReasons = null;
        protections = new ConcurrentHashMap<>();     // replaced, not cleared: the super constructor runs reload() first
    }

    /** The debris locked plots grow and how much of it must be cleared. */
    public Overgrowth.Settings clearing() {
        Overgrowth.Settings parsed = clearing;
        if (parsed == null) {
            parsed = Overgrowth.Settings.read(yaml().getConfigurationSection("clearing"), logger);
            clearing = parsed;
        }
        return parsed;
    }

    public int clearingBarLength() {
        return Math.clamp(yaml().getInt("clearing.progress-bar.length"), 1, 100);
    }

    public String clearingBarFilled() {
        return yaml().getString("clearing.progress-bar.filled");
    }

    public String clearingBarEmpty() {
        return yaml().getString("clearing.progress-bar.empty");
    }

    // Layout: read at startup only.

    public int grid() {
        return yaml().getInt("layout.grid", 5);
    }

    public int plotSize() {
        return yaml().getInt("layout.plot-size", 96);
    }

    public int groundY() {
        return yaml().getInt("layout.ground-y", 100);
    }

    /** Blocks written per tick across <em>all</em> Gardens together. */
    public int blocksPerTick() {
        return Math.max(256, yaml().getInt("generation.blocks-per-tick", 24_000));
    }

    /** Ticks between rows when a preset is applied to a plot. {@code 0} pastes at full speed. */
    public long pasteTicksPerRow() {
        return Math.max(0L, yaml().getLong("generation.paste-ticks-per-row", 12L));
    }

    /** Counted from the floor course up. */
    public int maxTemplateHeight() {
        return Math.clamp(yaml().getInt("generation.max-template-height", 48), 4, 256);
    }

    public Material surface() {
        return material("generation.blocks.surface", Material.GRASS_BLOCK);
    }

    public Material rim() {
        return material("generation.blocks.rim", Material.DIRT_PATH);
    }

    public Material subsoil() {
        return material("generation.blocks.subsoil", Material.DIRT);
    }

    public Material floor() {
        return material("generation.blocks.floor", Material.BEDROCK);
    }

    public boolean clearLegacyWalls() {
        return yaml().getBoolean("generation.clear-legacy-walls", true);
    }

    /** Whether crops and other ageable blocks are pasted at age 0 rather than as captured. */
    public boolean resetCropAge() {
        return yaml().getBoolean("presets.reset-crop-age", true);
    }

    /** Pasted as air, so a preset can never hand out a harvest. */
    public Set<Material> neverPaste() {
        return materials("presets.never-paste");
    }

    public boolean playerPresetsEnabled() {
        return yaml().getBoolean("presets.players.enabled", true);
    }

    /** Unless the preset sets its own. */
    public long presetCooldownMillis() {
        return Durations.parseMillis(yaml().getString("presets.players.cooldown", "1h"));
    }

    /** How long a first click stays armed, waiting for the confirming second click. */
    public long confirmMillis() {
        return Durations.parseMillis(yaml().getString("presets.players.confirm-window", "10s"));
    }

    public Difficulty difficulty() {
        String raw = yaml().getString("world.difficulty", "peaceful");
        try {
            return Difficulty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            logger.warning("world.difficulty '" + raw + "' is not a difficulty; using peaceful.");
            return Difficulty.PEACEFUL;
        }
    }

    public ConfigurationSection gamerules() {
        return yaml().getConfigurationSection("world.gamerules");
    }

    public double borderWarningDistance() {
        return yaml().getDouble("world.border.warning-distance", 0);
    }

    public double borderDamageAmount() {
        return yaml().getDouble("world.border.damage-amount", 0);
    }

    public double borderDamageBuffer() {
        return yaml().getDouble("world.border.damage-buffer", 0);
    }

    public boolean sweepVanillaMobs() {
        return yaml().getBoolean("world.sweep-vanilla-mobs", true);
    }

    /** Everything else is cancelled. */
    public Set<SpawnReason> allowedSpawnReasons() {
        Set<SpawnReason> parsed = spawnReasons;
        if (parsed == null) {
            parsed = readSpawnReasons();
            spawnReasons = parsed;
        }
        return parsed;
    }

    private Set<SpawnReason> readSpawnReasons() {
        Set<SpawnReason> out = EnumSet.noneOf(SpawnReason.class);
        for (String raw : yaml().getStringList("world.allowed-spawn-reasons")) {
            try {
                out.add(SpawnReason.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                logger.warning("world.allowed-spawn-reasons: '" + raw + "' is not a spawn reason.");
            }
        }
        return out;
    }

    /** A switch under {@code protection:}; every one defaults to on. */
    public boolean protect(String key) {
        return protections.computeIfAbsent(key, k -> yaml().getBoolean("protection." + k, true));
    }

    public boolean visitorsCanBuild() {
        return yaml().getBoolean("protection.visitors-can-build", false);
    }

    public boolean visitorsCanInteract() {
        return yaml().getBoolean("protection.visitors-can-interact", false);
    }

    /** Blocks in protected areas that may still be right-clicked (a lectern, a bell, a door). */
    public Set<Material> interactAllowed() {
        return materials("protection.interact-allow");
    }

    public boolean samEnabled() {
        return yaml().getBoolean("sam.enabled", true);
    }

    public String samName() {
        return yaml().getString("sam.name");
    }

    public String samProfession() {
        return yaml().getString("sam.profession", "farmer");
    }

    public String commandName(String which, String fallback) {
        String name = yaml().getString("commands." + which, fallback);
        return name == null || name.isBlank() ? fallback : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Where a reset Garden's occupants are sent; blank means the host's {@code spawn.world}. */
    public String evacuateWorld() {
        return yaml().getString("admin.evacuate-world", "");
    }

    /** The fallback (with a warning) if the name is wrong. */
    public Material material(String path, Material fallback) {
        String raw = yaml().getString(path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        Material found = Material.matchMaterial(raw.trim());
        if (found == null) {
            logger.warning(path + ": '" + raw + "' is not a material; using " + fallback.name().toLowerCase(Locale.ROOT) + ".");
            return fallback;
        }
        return found;
    }

    private Set<Material> materials(String path) {
        Set<Material> out = EnumSet.noneOf(Material.class);
        List<String> names = yaml().getStringList(path);
        for (String raw : names) {
            Material found = Material.matchMaterial(raw.trim());
            if (found == null) {
                logger.warning(path + ": '" + raw + "' is not a material.");
            } else {
                out.add(found);
            }
        }
        return out;
    }

    /**
     * Reads a cost the two ways RoyalSkyblock's upgrades accept one, so the host's currencies work here:
     *
     * <pre>
     *   cost: 5000 coins        cost:
     *                             currency: coins
     *                             amount: 5000
     * </pre>
     *
     * <p>{@code 0}, or nothing at all, is free.
     */
    public static Cost parseCost(ConfigurationSection parent, String key, Logger log) {
        if (parent == null) {
            return new Cost("", 0);
        }
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section != null) {
            return new Cost(section.getString("currency", "coins"), section.getDouble("amount", 0));
        }
        return parseCost(parent.getString(key), parent.getCurrentPath() + "." + key, log);
    }

    /** The compact {@code "5000 coins"} form. {@code where} names the setting in warnings. */
    public static Cost parseCost(String compact, String where, Logger log) {
        if (compact == null || compact.isBlank()) {
            return new Cost("", 0);
        }
        String[] parts = compact.trim().split("\\s+", 2);
        double amount;
        try {
            amount = Double.parseDouble(parts[0]);
        } catch (NumberFormatException notANumber) {
            if (log != null) {
                log.warning(where + ": '" + compact + "' is not a cost; expected e.g. '5000 coins'. "
                        + "Treating it as free.");
            }
            return new Cost("", 0);
        }
        return new Cost(parts.length > 1 ? parts[1].trim() : "coins", amount);
    }
}
