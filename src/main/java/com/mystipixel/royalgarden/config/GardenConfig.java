package com.mystipixel.royalgarden.config;

import com.mystipixel.royalskyblock.currency.Cost;
import org.bukkit.Difficulty;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;

import java.io.File;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

/**
 * {@code config.yml}: every number and switch The Garden runs on.
 *
 * <p>Typed accessors read the live file each time, so {@code /garden reload} takes effect on the next
 * call without anything holding a stale copy. The exception is {@code layout:}, which is read once at
 * startup by {@link com.mystipixel.royalgarden.garden.GardenLayout} — changing the shape of the board
 * under Gardens that were built to the old one would misplace every plot in them.
 */
public final class GardenConfig extends YamlFile {

    public GardenConfig(File dataFolder, Logger logger) {
        super(dataFolder, "config.yml", logger);
    }

    // ── layout (startup only) ──────────────────────────────────────────────────

    public int grid() {
        return yaml().getInt("layout.grid", 5);
    }

    public int plotSize() {
        return yaml().getInt("layout.plot-size", 96);
    }

    public int groundY() {
        return yaml().getInt("layout.ground-y", 100);
    }

    // ── generation ─────────────────────────────────────────────────────────────

    /** Blocks written per tick across <em>all</em> Gardens together. */
    public int blocksPerTick() {
        return Math.max(256, yaml().getInt("generation.blocks-per-tick", 24_000));
    }

    /** Ticks between rows when a preset is applied to a plot. {@code 0} pastes at full speed. */
    public long pasteTicksPerRow() {
        return Math.max(0L, yaml().getLong("generation.paste-ticks-per-row", 12L));
    }

    /** Tallest capture allowed, counted from the floor course up. */
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

    // ── presets ────────────────────────────────────────────────────────────────

    /** Whether crops and other ageable blocks are pasted at age 0 rather than as captured. */
    public boolean resetCropAge() {
        return yaml().getBoolean("presets.reset-crop-age", true);
    }

    /** Blocks that are pasted as air, so a preset can never hand out a harvest. */
    public Set<Material> neverPaste() {
        return materials("presets.never-paste");
    }

    public boolean playerPresetsEnabled() {
        return yaml().getBoolean("presets.players.enabled", true);
    }

    /** Default wait between two applies to the same plot, unless the preset sets its own. */
    public long presetCooldownMillis() {
        return Durations.parseMillis(yaml().getString("presets.players.cooldown", "1h"));
    }

    /** How long a first click stays armed, waiting for the confirming second click. */
    public long confirmMillis() {
        return Durations.parseMillis(yaml().getString("presets.players.confirm-window", "10s"));
    }

    // ── world rules ────────────────────────────────────────────────────────────

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

    /** Why a creature may spawn in a Garden. Everything else is cancelled. */
    public Set<SpawnReason> allowedSpawnReasons() {
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

    // ── protection ─────────────────────────────────────────────────────────────

    /** A protection switch under {@code protection:}; every one defaults to on. */
    public boolean protect(String key) {
        return yaml().getBoolean("protection." + key, true);
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

    // ── Sam and commands ───────────────────────────────────────────────────────

    public boolean samEnabled() {
        return yaml().getBoolean("sam.enabled", true);
    }

    public String samName() {
        return yaml().getString("sam.name", "&aSam &7— Garden Desk");
    }

    public String samProfession() {
        return yaml().getString("sam.profession", "farmer");
    }

    public String commandName(String which, String fallback) {
        String name = yaml().getString("commands." + which, fallback);
        return name == null || name.isBlank() ? fallback : name.trim().toLowerCase(Locale.ROOT);
    }

    /** Which world a reset Garden's occupants are sent to; blank means the host's {@code spawn.world}. */
    public String evacuateWorld() {
        return yaml().getString("admin.evacuate-world", "");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** A material by name, or the fallback (with a warning) if the name is wrong. */
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
     * Read a cost the same two ways RoyalSkyblock's upgrades accept one, so the currencies defined in
     * the host's {@code currencies:} section work here unchanged:
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

    /** Parse the compact {@code "5000 coins"} form. {@code where} names the setting in warnings. */
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
                log.warning(where + ": '" + compact + "' is not a cost — expected e.g. '5000 coins'. "
                        + "Treating it as free.");
            }
            return new Cost("", 0);
        }
        return new Cost(parts.length > 1 ? parts[1].trim() : "coins", amount);
    }
}
