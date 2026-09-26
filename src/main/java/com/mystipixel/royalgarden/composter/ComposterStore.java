package com.mystipixel.royalgarden.composter;

import com.mystipixel.royalgarden.garden.GardenStore;
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
 * Each player's Composter, one small YAML per player under {@code composter/}: what it holds, its clock,
 * and its upgrade levels.
 *
 * <p>Per player, not per Garden, like crop upgrades: the upgrades are paid for, so a {@code /garden
 * reset} for a new contest must not take them away. Written on every change — inserting and collecting
 * move items in and out of inventories, and a crash that forgot one would duplicate or lose them.
 * Concurrent, because placeholders are read off the server thread.
 *
 * <p>A file that does not parse is copied aside and that player's Composter is held read-only, the same
 * way visitors are.
 */
public final class ComposterStore {

    /** One player's Composter. Mutable; save it through {@link #save} after changing it. */
    public static final class Data {
        private volatile double matter;
        private volatile double fuel;
        private volatile long stored;
        private volatile long progress;
        private volatile long lastTick;
        private volatile long made;
        private volatile long collected;
        private final Map<String, Integer> upgrades = new ConcurrentHashMap<>();

        public ComposterMath.State state() {
            return new ComposterMath.State(matter, fuel, stored, progress);
        }

        public void apply(ComposterMath.State state) {
            this.matter = state.matter();
            this.fuel = state.fuel();
            this.stored = state.stored();
            this.progress = state.progress();
        }

        public double matter() {
            return matter;
        }

        public void setMatter(double matter) {
            this.matter = Math.max(0, matter);
        }

        public double fuel() {
            return fuel;
        }

        public void setFuel(double fuel) {
            this.fuel = Math.max(0, fuel);
        }

        /** Finished Compost waiting to be collected. */
        public long stored() {
            return stored;
        }

        public void setStored(long stored) {
            this.stored = Math.max(0, stored);
        }

        /** When the Composter was last caught up, or 0 if it never has been. */
        public long lastTick() {
            return lastTick;
        }

        public void setLastTick(long when) {
            this.lastTick = when;
        }

        /** Compost made in total, bonus included. */
        public long made() {
            return made;
        }

        public void addMade(long amount) {
            this.made += Math.max(0, amount);
        }

        /** Compost collected in total. */
        public long collected() {
            return collected;
        }

        public void setCollected(long collected) {
            this.collected = Math.max(0, collected);
        }

        public int level(String upgrade) {
            return upgrades.getOrDefault(upgrade, 0);
        }

        public void setLevel(String upgrade, int level) {
            if (level <= 0) {
                upgrades.remove(upgrade);
            } else {
                upgrades.put(upgrade, level);
            }
        }
    }

    private final File dir;
    private final Logger logger;
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>();
    private final Set<UUID> unreadable = ConcurrentHashMap.newKeySet();

    public ComposterStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "composter");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + " — Composters will not persist.");
        }
    }

    private File fileFor(UUID player) {
        return new File(dir, player + ".yml");
    }

    /** A player's Composter, read from disk the first time. */
    public Data get(UUID player) {
        return cache.computeIfAbsent(player, id -> read(id, true));
    }

    /**
     * A player's Composter for reading only: the cached copy if there is one, else read from disk
     * without caching it — for placeholders asked about offline players. Never write through it.
     */
    public Data peek(UUID player) {
        Data cached = cache.get(player);
        return cached != null ? cached : read(player, false);
    }

    /** Whether this player's file could not be read, so nothing of theirs may change. */
    public boolean readOnly(UUID player) {
        get(player);
        return unreadable.contains(player);
    }

    private Data read(UUID player, boolean keep) {
        Data data = new Data();
        File file = fileFor(player);
        if (!file.exists()) {
            return data;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException broken) {
            if (!keep) {
                return data;
            }
            File aside = new File(dir, player + ".yml.broken-" + System.currentTimeMillis());
            try {
                Files.copy(file.toPath(), aside.toPath());
            } catch (IOException copyFailed) {
                logger.log(Level.WARNING, "Could not copy " + file + " aside", copyFailed);
            }
            logger.severe("The Composter of " + player + " could not be read (" + broken.getMessage()
                    + "); a copy is at " + aside.getName() + " and it will not change until it is fixed.");
            unreadable.add(player);
            return data;
        }
        data.matter = Math.max(0, yaml.getDouble("organic-matter", 0));
        data.fuel = Math.max(0, yaml.getDouble("fuel", 0));
        data.stored = Math.max(0, yaml.getLong("stored", 0));
        data.progress = Math.max(0, yaml.getLong("progress", 0));
        data.lastTick = yaml.getLong("last-tick", 0);
        data.made = yaml.getLong("stats.made", 0);
        data.collected = yaml.getLong("stats.collected", 0);
        ConfigurationSection levels = yaml.getConfigurationSection("upgrades");
        if (levels != null) {
            for (String upgrade : levels.getKeys(false)) {
                data.setLevel(upgrade, levels.getInt(upgrade));
            }
        }
        return data;
    }

    /** Write one player now. Returns false if it could not be written, so the caller can undo. */
    public boolean save(UUID player) {
        Data data = cache.get(player);
        if (data == null || unreadable.contains(player)) {
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("organic-matter", data.matter);
        yaml.set("fuel", data.fuel);
        yaml.set("stored", data.stored);
        yaml.set("progress", data.progress);
        yaml.set("last-tick", data.lastTick);
        yaml.set("stats.made", data.made);
        yaml.set("stats.collected", data.collected);
        for (Map.Entry<String, Integer> entry : data.upgrades.entrySet()) {
            yaml.set("upgrades." + entry.getKey(), entry.getValue());
        }
        try {
            GardenStore.writeAtomically(fileFor(player).toPath(), yaml.saveToString());
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save the Composter of " + player, e);
            return false;
        }
    }

    /** Write every player held in memory. */
    public void saveAll() {
        for (UUID player : Set.copyOf(cache.keySet())) {
            save(player);
        }
    }

    /** Players whose Composter is held in memory. */
    public Set<UUID> loaded() {
        return Set.copyOf(cache.keySet());
    }

    /** Empty a player's Composter and forget its upgrades (admin). The file is moved aside, not deleted. */
    public void reset(UUID player) {
        cache.put(player, new Data());
        unreadable.remove(player);
        File file = fileFor(player);
        if (file.exists() && !file.renameTo(new File(dir, player + ".yml.reset-" + System.currentTimeMillis()))) {
            logger.warning("Could not move " + file + " aside.");
        }
    }

    /** Save and forget a player who has left. */
    public void unload(UUID player) {
        save(player);
        cache.remove(player);
        unreadable.remove(player);
    }
}
