package com.mystipixel.royalgarden.milestone;

import com.mystipixel.royalgarden.garden.YamlWriter;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each player's lifetime harvest per crop, one YAML per player under {@code milestones/}. Totals change in
 * memory and dirty players are written on a timer, on quit and on shutdown, on the writer thread. A file that does not parse is copied aside and that player is held read-only.
 */
public final class MilestoneStore {

    private final File dir;
    private final Logger logger;
    private final YamlWriter writer;
    private final Map<UUID, Map<String, Long>> totals = new HashMap<>();
    // concurrent: a failed write marks its player dirty again from the writer thread
    private final Set<UUID> dirty = ConcurrentHashMap.newKeySet();
    private final Set<UUID> unreadable = new HashSet<>();

    public MilestoneStore(File dataFolder, Logger logger, YamlWriter writer) {
        this.dir = new File(dataFolder, "milestones");
        this.logger = logger;
        this.writer = writer;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + "; milestones will not persist.");
        }
    }

    private File fileFor(UUID player) {
        return new File(dir, player + ".yml");
    }

    /** Read from disk the first time. */
    public Map<String, Long> totals(UUID player) {
        return totals.computeIfAbsent(player, this::read);
    }

    public long total(UUID player, String crop) {
        return totals(player).getOrDefault(crop, 0L);
    }

    /** Returns the new total. */
    public long add(UUID player, String crop, long amount) {
        long now = totals(player).merge(crop, amount, Long::sum);
        dirty.add(player);
        return now;
    }

    public void set(UUID player, String crop, long amount) {
        totals(player).put(crop, Math.max(0, amount));
        dirty.add(player);
    }

    /** The file is moved aside, not deleted. */
    public void reset(UUID player) {
        totals.put(player, new HashMap<>());
        unreadable.remove(player);
        File file = fileFor(player);
        writer.flush(file.toPath());
        if (file.exists() && !file.renameTo(new File(dir, player + ".yml.reset-" + System.currentTimeMillis()))) {
            logger.warning("Could not move " + file + " aside.");
        }
        dirty.add(player);
    }

    private Map<String, Long> read(UUID player) {
        Map<String, Long> out = new HashMap<>();
        File file = fileFor(player);
        if (!writer.exists(file)) {
            return out;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            writer.load(yaml, file);
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

    /** Only if they have unsaved progress. */
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
        writer.write(fileFor(player).toPath(), yaml.saveToString(), () -> dirty.add(player));
    }

    public void saveAll() {
        for (UUID player : Set.copyOf(dirty)) {
            save(player);
        }
    }

    public void unload(UUID player) {
        save(player);
        totals.remove(player);
        unreadable.remove(player);
    }
}
