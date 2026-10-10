package com.mystipixel.royalgarden.garden;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Garden state: one YAML per Garden in the extension's own folder. Not in the host's {@code Storage}
 * (island/profile/bank-shaped, no generic key-value) and not in world PDC (whether it survives ASP's
 * SlimeWorld serialisation is unknown). Saves go through a temp file moved into place; a file that does
 * not parse is copied aside and its Garden held read-only.
 */
public final class GardenStore {

    private final File dir;
    private final Logger logger;
    private final Map<UUID, GardenState> cache = new HashMap<>();

    public GardenStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "gardens");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + "; Garden unlocks will not persist.");
        }
    }

    /** Read from disk the first time and cached after. */
    public GardenState get(UUID owner) {
        return cache.computeIfAbsent(owner, this::read);
    }

    public boolean exists(UUID owner) {
        return cache.containsKey(owner) || fileFor(owner).exists();
    }

    private File fileFor(UUID owner) {
        return new File(dir, owner + ".yml");
    }

    private GardenState read(UUID owner) {
        GardenState state = new GardenState(owner);
        File file = fileFor(owner);
        if (!file.exists()) {
            return state;                       // brand-new Garden: centre plot only
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException unreadable) {
            File aside = new File(dir, owner + ".yml.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file.toPath(), aside.toPath());
            } catch (IOException copyFailed) {
                logger.log(Level.WARNING, "Could not copy " + file + " aside", copyFailed);
            }
            logger.severe("Garden " + owner + " could not be read (" + unreadable.getMessage() + "). A copy "
                    + "is at " + aside.getName() + ". The Garden is read-only until it is fixed or reset "
                    + "with /garden reset.");
            state.markReadOnly();
            return state;
        }

        for (String entry : yaml.getStringList("unlocked")) {
            int[] plot = parsePair(entry);
            if (plot == null) {
                logger.warning("Garden " + owner + ": ignoring malformed plot '" + entry + "'.");
                continue;
            }
            state.forceUnlock(plot[0], plot[1]);
        }
        readMap(yaml.getConfigurationSection("presets"), (k, v) -> state.presetsByKey().put(k, v.toString()));
        readMap(yaml.getConfigurationSection("applied"), (k, v) -> {
            if (v instanceof Number n) {
                state.appliedByKey().put(k, n.longValue());
            }
        });
        readMap(yaml.getConfigurationSection("pending"), (k, v) -> state.pendingByKey().put(k, v.toString()));
        readMap(yaml.getConfigurationSection("overgrowth"), (k, v) -> {
            if (v instanceof Number n) {
                state.overgrowthByKey().put(k, n.intValue());
            }
        });
        readMap(yaml.getConfigurationSection("clearing"), (k, v) -> {
            if (v instanceof ConfigurationSection progress) {
                int total = Math.max(0, progress.getInt("total"));
                state.clearingByKey().put(k, new int[]{total, Math.clamp(progress.getInt("cleared"), 0, total)});
            }
        });
        state.setLegacyWallsCleared(yaml.getBoolean("legacy-walls-cleared", false));
        state.setBarnVersion(yaml.getInt("barn-version", 1));        // none recorded: the original barn
        state.setFarmhouse(yaml.getString("farmhouse"));
        state.setXp(yaml.getLong("xp", 0));
        return state;
    }

    private interface Entry {
        void accept(int key, Object value);
    }

    // A plot-name: value section, skipping names that are not on this board.
    private static void readMap(ConfigurationSection section, Entry into) {
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            int[] plot = GardenLayout.parsePlot(name);
            Object value = section.get(name);
            if (plot != null && value != null) {
                into.accept(GardenState.keyOf(plot[0], plot[1]), value);
            }
        }
    }

    private static int[] parsePair(String entry) {
        String[] parts = entry.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException notAPlot) {
            return null;
        }
    }

    /** Called on every change, so a crash never costs a player a plot they just bought. */
    public void save(GardenState state) {
        if (state.readOnly()) {
            logger.warning("Not saving Garden " + state.owner() + ": it could not be read, so saving would "
                    + "overwrite it. Fix or reset it first.");
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> plots = new ArrayList<>();
        for (int[] plot : state.plots()) {
            plots.add(plot[0] + "," + plot[1]);
        }
        yaml.set("unlocked", plots);
        writeMap(yaml, "presets", state.presetsByKey());
        writeMap(yaml, "applied", state.appliedByKey());
        writeMap(yaml, "pending", state.pendingByKey());
        writeMap(yaml, "overgrowth", state.overgrowthByKey());
        for (Map.Entry<Integer, int[]> entry : state.clearingByKey().entrySet()) {
            int[] plot = GardenState.plotOf(entry.getKey());
            String path = "clearing." + GardenLayout.plotName(plot[0], plot[1]);
            yaml.set(path + ".total", entry.getValue()[0]);
            yaml.set(path + ".cleared", entry.getValue()[1]);
        }
        yaml.set("legacy-walls-cleared", state.legacyWallsCleared());
        yaml.set("barn-version", state.barnVersion());
        yaml.set("farmhouse", state.farmhouse());
        yaml.set("xp", state.xp());
        try {
            writeAtomically(fileFor(state.owner()).toPath(), yaml.saveToString());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save Garden state for " + state.owner(), e);
        }
    }

    private static void writeMap(YamlConfiguration yaml, String path, Map<Integer, ?> values) {
        for (Map.Entry<Integer, ?> entry : values.entrySet()) {
            int[] plot = GardenState.plotOf(entry.getKey());
            yaml.set(path + "." + GardenLayout.plotName(plot[0], plot[1]), entry.getValue());
        }
    }

    /**
     * Forget a Garden's state file and cached copy; the next {@link #get} starts afresh. The file is
     * renamed, not deleted, so a reset on the wrong player can be undone.
     */
    public void reset(UUID owner) {
        cache.remove(owner);
        File file = fileFor(owner);
        if (file.exists()) {
            File aside = new File(dir, owner + ".yml.reset-" + System.currentTimeMillis());
            if (!file.renameTo(aside)) {
                logger.warning("Could not move " + file + " aside; deleting it instead.");
                if (!file.delete()) {
                    logger.severe("Could not delete " + file + " either; the reset will not stick.");
                }
            }
        }
    }

    /** The next read of each Garden comes from disk. */
    public void invalidate() {
        cache.clear();
    }

    /** A crash leaves either the old contents or the new, never half of each. */
    public static void writeAtomically(Path target, String contents) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(temp, contents, StandardCharsets.UTF_8);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
