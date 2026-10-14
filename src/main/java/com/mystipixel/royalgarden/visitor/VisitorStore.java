package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.garden.GardenStore;
import com.mystipixel.royalgarden.garden.PeekCache;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each player's visitors, one YAML per player under {@code visitors/}. Every change is written straight
 * away: accepting takes items, and a lost write would let the same visitor be paid twice. An unparseable
 * file is copied aside and the player held read-only.
 */
public final class VisitorStore {

    /** Mutable; save it through {@link #save} after changing it. */
    public static final class Data {
        private final LinkedHashMap<String, Offer> queue = new LinkedHashMap<>();
        private final Map<String, Long> servedBy = new ConcurrentHashMap<>();
        private long lastArrival;
        private long lastSeen;
        private long served;
        private long refused;
        private long copper;

        /** In arrival order. */
        public List<Offer> queue() {
            return List.copyOf(queue.values());
        }

        public int queued() {
            return queue.size();
        }

        public Offer offer(String id) {
            return id == null ? null : queue.get(id);
        }

        public boolean waiting(String visitor) {
            for (Offer offer : queue.values()) {
                if (offer.visitor().equals(visitor)) {
                    return true;
                }
            }
            return false;
        }

        public void add(Offer offer) {
            queue.put(offer.id(), offer);
        }

        public Offer remove(String id) {
            return queue.remove(id);
        }

        public void clearQueue() {
            queue.clear();
        }

        public long lastArrival() {
            return lastArrival;
        }

        public void setLastArrival(long when) {
            this.lastArrival = when;
        }

        /** When the player last left, or 0 while they are online. */
        public long lastSeen() {
            return lastSeen;
        }

        public void setLastSeen(long when) {
            this.lastSeen = when;
        }

        public long served() {
            return served;
        }

        public long served(String visitor) {
            return servedBy.getOrDefault(visitor, 0L);
        }

        /** Visitors served at least once. */
        public int unique() {
            return servedBy.size();
        }

        public long refused() {
            return refused;
        }

        public long copper() {
            return copper;
        }

        public void recordServed(String visitor, long copperPaid) {
            served++;
            servedBy.merge(visitor, 1L, Long::sum);
            copper += Math.max(0, copperPaid);
        }

        public void recordRefused() {
            refused++;
        }

        /** Admin: clears the counts; the queue and clock are left alone. */
        public void resetStats() {
            served = 0;
            refused = 0;
            copper = 0;
            servedBy.clear();
        }
    }

    private final File dir;
    private final Logger logger;
    // Concurrent: placeholders are often asked for off the main thread (scoreboards, tab lists).
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>();
    private final PeekCache<Data> peeked = new PeekCache<>();
    private final Set<UUID> unreadable = ConcurrentHashMap.newKeySet();

    public VisitorStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "visitors");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + "; visitors will not persist.");
        }
    }

    private File fileFor(UUID player) {
        return new File(dir, player + ".yml");
    }

    /** Read from disk the first time. */
    public Data get(UUID player) {
        return cache.computeIfAbsent(player, id -> read(id, true));
    }

    /**
     * Read-only view for placeholders about offline players: the cached copy, or a copy read from disk at most
     * 30 seconds ago. Never write through it.
     */
    public Data peek(UUID player) {
        Data cached = cache.get(player);
        return cached != null ? cached : peeked.get(player, id -> read(id, false));
    }

    /** True if this player's file could not be read, so nothing of theirs may change. */
    public boolean readOnly(UUID player) {
        get(player);
        return unreadable.contains(player);
    }

    // keep: whether the result will be cached. Only then is a broken file copied aside and marked
    // read-only, so repeated peeks copy nothing.
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
            logger.severe("Visitors for " + player + " could not be read (" + broken.getMessage() + "); a copy "
                    + "is at " + aside.getName() + " and they will not change until it is fixed.");
            unreadable.add(player);
            return data;
        }
        data.lastArrival = yaml.getLong("last-arrival", 0);
        data.lastSeen = yaml.getLong("last-seen", 0);
        data.served = yaml.getLong("stats.served", 0);
        data.refused = yaml.getLong("stats.refused", 0);
        data.copper = yaml.getLong("stats.copper", 0);
        ConfigurationSection by = yaml.getConfigurationSection("stats.by-visitor");
        if (by != null) {
            for (String visitor : by.getKeys(false)) {
                data.servedBy.put(visitor, by.getLong(visitor));
            }
        }
        ConfigurationSection queue = yaml.getConfigurationSection("queue");
        if (queue != null) {
            for (String id : queue.getKeys(false)) {
                ConfigurationSection o = queue.getConfigurationSection(id);
                if (o == null || o.getString("visitor") == null) {
                    continue;
                }
                List<Offer.Wanted> items = new ArrayList<>();
                // "amount|item|name": items can hold ':' and '.', which YAML keys cannot.
                for (String line : o.getStringList("items")) {
                    String[] parts = line.split("\\|", 3);
                    try {
                        items.add(new Offer.Wanted(parts[1], Long.parseLong(parts[0]), parts.length > 2 ? parts[2] : ""));
                    } catch (RuntimeException malformed) {
                        logger.warning("Visitors for " + player + ": ignoring malformed item '" + line + "'.");
                    }
                }
                if (!items.isEmpty()) {
                    data.add(new Offer(id, o.getString("visitor"), List.copyOf(items), o.getLong("copper"),
                            o.getLong("xp"), o.getLong("arrived")));
                }
            }
        }
        return data;
    }

    public void save(UUID player) {
        Data data = cache.get(player);
        if (data == null || unreadable.contains(player)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("last-arrival", data.lastArrival);
        yaml.set("last-seen", data.lastSeen);
        for (Offer offer : data.queue.values()) {
            String path = "queue." + offer.id();
            yaml.set(path + ".visitor", offer.visitor());
            yaml.set(path + ".arrived", offer.arrived());
            yaml.set(path + ".copper", offer.copper());
            yaml.set(path + ".xp", offer.xp());
            List<String> items = new ArrayList<>();
            for (Offer.Wanted wanted : offer.items()) {
                items.add(wanted.amount() + "|" + wanted.item() + "|" + wanted.name());
            }
            yaml.set(path + ".items", items);
        }
        yaml.set("stats.served", data.served);
        yaml.set("stats.refused", data.refused);
        yaml.set("stats.copper", data.copper);
        for (Map.Entry<String, Long> entry : data.servedBy.entrySet()) {
            yaml.set("stats.by-visitor." + entry.getKey(), entry.getValue());
        }
        try {
            GardenStore.writeAtomically(fileFor(player).toPath(), yaml.saveToString());
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save visitors for " + player, e);
        }
    }

    public void saveAll() {
        for (UUID player : Set.copyOf(cache.keySet())) {
            save(player);
        }
    }

    public void unload(UUID player) {
        save(player);
        cache.remove(player);
        unreadable.remove(player);
        peeked.forget(player);
    }
}
