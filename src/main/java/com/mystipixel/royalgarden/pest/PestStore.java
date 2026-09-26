package com.mystipixel.royalgarden.pest;

import com.mystipixel.royalgarden.garden.GardenLayout;
import com.mystipixel.royalgarden.garden.GardenStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pests per player, one small YAML per player under {@code pests/}: the pests alive in their Garden, and
 * the pests they have killed.
 *
 * <p>The live pests are the Garden's own record of its tagged entities — kind, plot and where each first
 * appeared — so counts, caps and placeholders hold while the plot's chunks, or the whole Garden, are not
 * loaded. The world stays the judge: an entity that loads without a record is taken in if there is room
 * and removed if not, and a record whose chunk is loaded without its entity is dropped.
 *
 * <p>Written on every change, since kills pay out. Concurrent, because placeholders are read off the
 * server thread. A file that does not parse is copied aside and that player's pests are held read-only,
 * the same way visitors are.
 */
public final class PestStore {

    /**
     * One pest alive in a Garden.
     *
     * @param plot    the plot it belongs to, packed {@code pz * grid + px}
     * @param crop    the crop being farmed when it came, or blank
     * @param spawned when it appeared
     */
    public record Alive(String type, int plot, String crop, double x, double y, double z, long spawned) {
    }

    /** One player's pests. Mutable; save it through {@link #save} after changing it. */
    public static final class Data {
        private final Map<UUID, Alive> alive = new ConcurrentHashMap<>();
        private final Map<String, Long> killsByType = new ConcurrentHashMap<>();
        private volatile long kills;
        private volatile long lastSpawn;
        private volatile long clearedAt;

        /** Every pest alive in this Garden, by entity id. A live view — copy it to iterate while changing. */
        public Map<UUID, Alive> alive() {
            return alive;
        }

        public void addAlive(UUID entity, Alive pest) {
            alive.put(entity, pest);
        }

        public Alive removeAlive(UUID entity) {
            return alive.remove(entity);
        }

        public void clearAlive() {
            alive.clear();
        }

        /** Pests per plot. */
        public Map<Integer, Integer> counts() {
            Map<Integer, Integer> out = new HashMap<>();
            for (Alive pest : alive.values()) {
                out.merge(pest.plot(), 1, Integer::sum);
            }
            return out;
        }

        public int count(int plot) {
            int count = 0;
            for (Alive pest : alive.values()) {
                if (pest.plot() == plot) {
                    count++;
                }
            }
            return count;
        }

        public int total() {
            return alive.size();
        }

        /** Pests killed in total. */
        public long kills() {
            return kills;
        }

        public long kills(String type) {
            return killsByType.getOrDefault(type, 0L);
        }

        public void recordKill(String type) {
            kills++;
            killsByType.merge(type, 1L, Long::sum);
        }

        public void resetStats() {
            kills = 0;
            killsByType.clear();
        }

        /** When the last pest came, for the cooldown; 0 if none ever has. */
        public long lastSpawn() {
            return lastSpawn;
        }

        public void setLastSpawn(long when) {
            this.lastSpawn = when;
        }

        /** When the Garden was last cleared: a tagged pest from before then is never taken back in. */
        public long clearedAt() {
            return clearedAt;
        }

        public void setClearedAt(long when) {
            this.clearedAt = when;
        }
    }

    private final File dir;
    private final Logger logger;
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>();
    private final Set<UUID> unreadable = ConcurrentHashMap.newKeySet();

    public PestStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "pests");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + " — pests will not persist.");
        }
    }

    private File fileFor(UUID player) {
        return new File(dir, player + ".yml");
    }

    /** A player's pests, read from disk the first time. */
    public Data get(UUID player) {
        return cache.computeIfAbsent(player, id -> read(id, true));
    }

    /**
     * A player's pests for reading only: the cached copy if there is one, else read from disk without
     * caching it — for placeholders asked about offline players. Never write through it.
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
            logger.severe("The pests of " + player + " could not be read (" + broken.getMessage()
                    + "); a copy is at " + aside.getName() + " and they will not change until it is fixed.");
            unreadable.add(player);
            return data;
        }
        data.kills = Math.max(0, yaml.getLong("stats.kills", 0));
        ConfigurationSection byType = yaml.getConfigurationSection("stats.by-type");
        if (byType != null) {
            for (String type : byType.getKeys(false)) {
                long count = byType.getLong(type);
                if (count > 0) {
                    data.killsByType.put(type, count);
                }
            }
        }
        data.lastSpawn = yaml.getLong("last-spawn", 0);
        data.clearedAt = yaml.getLong("cleared-at", 0);
        ConfigurationSection alive = yaml.getConfigurationSection("alive");
        if (alive != null) {
            for (String key : alive.getKeys(false)) {
                ConfigurationSection pest = alive.getConfigurationSection(key);
                int[] plot = pest == null ? null : GardenLayout.parsePlot(pest.getString("plot"));
                UUID entity;
                try {
                    entity = UUID.fromString(key);
                } catch (IllegalArgumentException notAnId) {
                    continue;
                }
                if (plot == null) {
                    continue;                      // a plot the board no longer has
                }
                data.alive.put(entity, new Alive(pest.getString("type", ""), pack(plot[0], plot[1]),
                        pest.getString("crop", ""), pest.getDouble("x"), pest.getDouble("y"), pest.getDouble("z"),
                        pest.getLong("spawned", 0)));
            }
        }
        return data;
    }

    /** Write one player now. Returns false if it could not be written. */
    public boolean save(UUID player) {
        Data data = cache.get(player);
        if (data == null || unreadable.contains(player)) {
            return false;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("stats.kills", data.kills);
        for (Map.Entry<String, Long> entry : data.killsByType.entrySet()) {
            yaml.set("stats.by-type." + entry.getKey(), entry.getValue());
        }
        yaml.set("last-spawn", data.lastSpawn);
        yaml.set("cleared-at", data.clearedAt);
        for (Map.Entry<UUID, Alive> entry : data.alive.entrySet()) {
            Alive pest = entry.getValue();
            String path = "alive." + entry.getKey();
            yaml.set(path + ".type", pest.type());
            yaml.set(path + ".plot", plotName(pest.plot()));
            yaml.set(path + ".crop", pest.crop());
            yaml.set(path + ".x", pest.x());
            yaml.set(path + ".y", pest.y());
            yaml.set(path + ".z", pest.z());
            yaml.set(path + ".spawned", pest.spawned());
        }
        try {
            GardenStore.writeAtomically(fileFor(player).toPath(), yaml.saveToString());
            return true;
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save the pests of " + player, e);
            return false;
        }
    }

    /** Write every player held in memory. */
    public void saveAll() {
        for (UUID player : Set.copyOf(cache.keySet())) {
            save(player);
        }
    }

    /** Players whose pests are held in memory. */
    public Set<UUID> loaded() {
        return Set.copyOf(cache.keySet());
    }

    /** Save and forget a player — unless their Garden is still loaded, whose pests are still being watched. */
    public void unload(UUID player) {
        save(player);
        cache.remove(player);
        unreadable.remove(player);
    }

    // ── plots ──────────────────────────────────────────────────────────────────

    public static int pack(int px, int pz) {
        return pz * GardenLayout.grid() + px;
    }

    public static int px(int plot) {
        return plot % GardenLayout.grid();
    }

    public static int pz(int plot) {
        return plot / GardenLayout.grid();
    }

    public static String plotName(int plot) {
        return GardenLayout.plotName(px(plot), pz(plot));
    }
}
