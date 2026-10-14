package com.mystipixel.royalgarden.upgrade;

import com.mystipixel.royalgarden.garden.GardenStore;
import com.mystipixel.royalgarden.garden.PeekCache;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each player's crop upgrade levels, one YAML per player under {@code upgrades/}. Per player, not per
 * Garden, so {@code /garden reset} never takes them. Written on every change; concurrent because
 * placeholders read it off the main thread.
 */
public final class UpgradeStore {

    private final File dir;
    private final Logger logger;
    private final Map<UUID, Map<String, Integer>> cache = new ConcurrentHashMap<>();
    private final PeekCache<Map<String, Integer>> peeked = new PeekCache<>();
    private final Set<UUID> unreadable = ConcurrentHashMap.newKeySet();

    public UpgradeStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "upgrades");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + "; crop upgrades will not persist.");
        }
    }

    private File fileFor(UUID player) {
        return new File(dir, player + ".yml");
    }

    private Map<String, Integer> levels(UUID player) {
        return cache.computeIfAbsent(player, id -> read(id, true));
    }

    /** A crop's upgrade level for a player; 0 if never upgraded. */
    public int level(UUID player, String crop) {
        return levels(player).getOrDefault(crop, 0);
    }

    /** {@link #level} without loading an offline player; their file is read at most every 30 seconds. */
    public int peek(UUID player, String crop) {
        Map<String, Integer> cached = cache.get(player);
        return (cached != null ? cached : peeked.get(player, id -> read(id, false))).getOrDefault(crop, 0);
    }

    /** True if this player's file could not be read, so their levels must not change. */
    public boolean readOnly(UUID player) {
        levels(player);
        return unreadable.contains(player);
    }

    /** Set a level and save. Returns false if the player's file is unreadable. */
    public boolean set(UUID player, String crop, int level) {
        if (readOnly(player)) {
            return false;
        }
        if (level <= 0) {
            levels(player).remove(crop);
        } else {
            levels(player).put(crop, level);
        }
        save(player);
        return true;
    }

    /** The file is moved aside, not deleted. */
    public void reset(UUID player) {
        cache.put(player, new ConcurrentHashMap<>());
        unreadable.remove(player);
        peeked.forget(player);
        File file = fileFor(player);
        if (file.exists() && !file.renameTo(new File(dir, player + ".yml.reset-" + System.currentTimeMillis()))) {
            logger.warning("Could not move " + file + " aside.");
        }
    }

    private Map<String, Integer> read(UUID player, boolean keep) {
        Map<String, Integer> out = new ConcurrentHashMap<>();
        File file = fileFor(player);
        if (!file.exists()) {
            return out;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException broken) {
            if (!keep) {
                return out;
            }
            File aside = new File(dir, player + ".yml.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file.toPath(), aside.toPath());
            } catch (IOException copyFailed) {
                logger.log(Level.WARNING, "Could not copy " + file + " aside", copyFailed);
            }
            logger.severe("Crop upgrades for " + player + " could not be read (" + broken.getMessage()
                    + "); a copy is at " + aside.getName() + " and they will not change until it is fixed.");
            unreadable.add(player);
            return out;
        }
        ConfigurationSection crops = yaml.getConfigurationSection("levels");
        if (crops != null) {
            for (String crop : crops.getKeys(false)) {
                int level = crops.getInt(crop);
                if (level > 0) {
                    out.put(crop, level);
                }
            }
        }
        return out;
    }

    private void save(UUID player) {
        Map<String, Integer> values = cache.get(player);
        if (values == null || unreadable.contains(player)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, Integer> entry : values.entrySet()) {
            yaml.set("levels." + entry.getKey(), entry.getValue());
        }
        try {
            GardenStore.writeAtomically(fileFor(player).toPath(), yaml.saveToString());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save crop upgrades for " + player, e);
        }
    }

    public void unload(UUID player) {
        cache.remove(player);
        unreadable.remove(player);
        peeked.forget(player);
    }
}
