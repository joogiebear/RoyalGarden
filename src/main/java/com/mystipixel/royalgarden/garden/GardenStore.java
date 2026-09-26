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
 * Where Garden state lives: one small YAML per Garden, in the extension's own data folder.
 *
 * <p>Not in the host's {@code Storage}. That interface is entirely island-, profile- and bank-shaped —
 * there is no generic key-value on it — so putting Garden state there would mean changing
 * RoyalSkyblock's schema, which is exactly the coupling being an extension is meant to avoid.
 *
 * <p>Not in the world either, tempting as it is: a Garden world is already per-player, so its
 * persistent data container looks like the natural home. But SlimeWorlds persist through ASP's own
 * serialisation, and whether Paper's world PDC survives that round trip is not something to find out
 * by losing somebody's plots.
 *
 * <h2>Not losing it</h2>
 *
 * <p>Two things used to be able to wipe a Garden. A crash in the middle of a save left a truncated file;
 * and a file that failed to parse was read as empty, so the player's next unlock saved that emptiness
 * over it. Saves now go to a temporary file that is moved into place, and a file that does not parse is
 * copied aside and its Garden held read-only until an admin looks at it.
 */
public final class GardenStore {

    private final File dir;
    private final Logger logger;
    private final Map<UUID, GardenState> cache = new HashMap<>();

    public GardenStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "gardens");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + " — Garden unlocks will not persist.");
        }
    }

    /** A player's state, read from disk the first time and cached after. */
    public GardenState get(UUID owner) {
        return cache.computeIfAbsent(owner, this::read);
    }

    /** Whether a player has ever had a Garden saved. */
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
        state.setXp(yaml.getLong("xp", 0));
        return state;
    }

    private interface Entry {
        void accept(int key, Object value);
    }

    /** Read a {@code plot-name: value} section, skipping names that are not on this board. */
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

    /**
     * Write a Garden's state to disk.
     *
     * <p>Called on every change rather than on a timer or at shutdown. One change is one small file
     * write, and the alternative is a crash costing a player a plot they watched themselves buy.
     */
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
     * Forget a Garden: its state file and the cached copy. The next {@link #get} starts it afresh.
     *
     * <p>The file is renamed rather than deleted, so a reset run on the wrong player can be undone by
     * moving it back.
     */
    public void reset(UUID owner) {
        cache.remove(owner);
        File file = fileFor(owner);
        if (file.exists()) {
            File aside = new File(dir, owner + ".yml.reset-" + System.currentTimeMillis());
            if (!file.renameTo(aside)) {
                logger.warning("Could not move " + file + " aside; deleting it instead.");
                if (!file.delete()) {
                    logger.severe("Could not delete " + file + " either — the reset will not stick.");
                }
            }
        }
    }

    /** Drop every cached state, so the next read of each comes from disk. */
    public void invalidate() {
        cache.clear();
    }

    /** Write a file so that a crash leaves either the old contents or the new, never half of each. */
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
