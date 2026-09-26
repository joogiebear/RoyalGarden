package com.mystipixel.royalgarden.milestone;

import com.mystipixel.royalgarden.garden.GardenStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each player's lifetime harvest per crop, one small YAML per player under {@code milestones/}.
 *
 * <p>Harvests are far too frequent to write a file each — a farmer breaks tens of blocks a second — so
 * totals change in memory and are marked dirty, and dirty players are written on a timer, when they
 * leave, and on shutdown. The most a crash can cost is one save interval of progress, and never a file:
 * writes go through a temporary file that is moved into place.
 *
 * <p>A file that does not parse is copied aside and that player's progress is held read-only in memory,
 * so a bad character cannot cost someone their milestones on the next save.
 */
public final class MilestoneStore {

    private final File dir;
    private final Logger logger;
    private final Map<UUID, Map<String, Long>> totals = new HashMap<>();
    private final Set<UUID> dirty = new HashSet<>();
    private final Set<UUID> unreadable = new HashSet<>();

    public MilestoneStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "milestones");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + " — milestones will not persist.");
        }
    }

    private File fileFor(UUID player) {
        return new File(dir, player + ".yml");
    }

    /** A player's totals by crop id, read from disk the first time. */
    public Map<String, Long> totals(UUID player) {
        return totals.computeIfAbsent(player, this::read);
    }

    public long total(UUID player, String crop) {
        return totals(player).getOrDefault(crop, 0L);
    }

    /** Add to a total and return the new one. */
    public long add(UUID player, String crop, long amount) {
        long now = totals(player).merge(crop, amount, Long::sum);
        dirty.add(player);
        return now;
    }

    /** Set a total outright (admin). */
    public void set(UUID player, String crop, long amount) {
        totals(player).put(crop, Math.max(0, amount));
        dirty.add(player);
    }

    /** Forget every total for a player (admin). The file is moved aside, not deleted. */
    public void reset(UUID player) {
        totals.put(player, new HashMap<>());
        unreadable.remove(player);
        File file = fileFor(player);
        if (file.exists() && !file.renameTo(new File(dir, player + ".yml.reset-" + System.currentTimeMillis()))) {
            logger.warning("Could not move " + file + " aside.");
        }
        dirty.add(player);
    }

    private Map<String, Long> read(UUID player) {
        Map<String, Long> out = new HashMap<>();
        File file = fileFor(player);
        if (!file.exists()) {
            return out;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException broken) {
            File aside = new File(dir, player + ".yml.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file.toPath(), aside.toPath());
            } catch (IOException copyFailed) {
                logger.log(Level.WARNING, "Could not copy " + file + " aside", copyFailed);
            }
            logger.severe("Milestones for " + player + " could not be read (" + broken.getMessage() + "); a copy "
                    + "is at " + aside.getName() + " and they will not be saved until fixed or reset.");
            unreadable.add(player);
            return out;
        }
        ConfigurationSection crops = yaml.getConfigurationSection("crops");
        if (crops != null) {
            for (String crop : crops.getKeys(false)) {
                out.put(crop, Math.max(0, crops.getLong(crop)));
            }
        }
        return out;
    }

    /** Write one player now, if they have unsaved progress. */
    public void save(UUID player) {
        if (!dirty.remove(player)) {
            return;
        }
        if (unreadable.contains(player)) {
            return;
        }
        Map<String, Long> values = totals.get(player);
        if (values == null) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Long> entry : values.entrySet()) {
            yaml.set("crops." + entry.getKey(), entry.getValue());
        }
        try {
            GardenStore.writeAtomically(fileFor(player).toPath(), yaml.saveToString());
        } catch (IOException e) {
            dirty.add(player);
            logger.log(Level.SEVERE, "Could not save milestones for " + player, e);
        }
    }

    /** Write everyone with unsaved progress. */
    public void saveAll() {
        for (UUID player : Set.copyOf(dirty)) {
            save(player);
        }
    }

    /** Save and forget a player who has left, so memory tracks who is online. */
    public void unload(UUID player) {
        save(player);
        totals.remove(player);
        unreadable.remove(player);
    }
}
