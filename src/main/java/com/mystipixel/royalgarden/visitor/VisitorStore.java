package com.mystipixel.royalgarden.visitor;

import com.mystipixel.royalgarden.garden.GardenStore;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Each player's visitors, one small YAML per player under {@code visitors/}: who is waiting, the arrival
 * clock, and how many they have served.
 *
 * <p>Unlike milestones, every change is written straight away. Changes are rare — an arrival every few
 * minutes, a click to accept — and accepting takes items out of an inventory, so a crash that forgot an
 * accept would hand the same visitor back to be paid twice.
 *
 * <p>A file that does not parse is copied aside and that player's visitors are held read-only in
 * memory, the same way milestones are.
 */
public final class VisitorStore {

    /** One player's visitor data. Mutable; save it through {@link #save} after changing it. */
    public static final class Data {
        private final LinkedHashMap<String, Offer> queue = new LinkedHashMap<>();
        private final Map<String, Long> servedBy = new HashMap<>();
        private long lastArrival;
        private long lastSeen;
        private long served;
        private long refused;
        private long copper;

        /** Waiting visitors in arrival order. */
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

        /** Visitors served in total. */
        public long served() {
            return served;
        }

        /** How many times one visitor has been served. */
        public long served(String visitor) {
            return servedBy.getOrDefault(visitor, 0L);
        }

        /** How many different visitors have been served at least once. */
        public int unique() {
            return servedBy.size();
        }

        public long refused() {
            return refused;
        }

        /** Copper earned from visitors in total. */
        public long copper() {
            return copper;
        }

        /** Count one served visitor and what it paid. */
        public void recordServed(String visitor, long copperPaid) {
            served++;
            servedBy.merge(visitor, 1L, Long::sum);
            copper += Math.max(0, copperPaid);
        }

        public void recordRefused() {
            refused++;
        }

        /** Forget the counts (admin). The queue and clock are left alone. */
        public void resetStats() {
            served = 0;
            refused = 0;
            copper = 0;
            servedBy.clear();
        }
    }

    private final File dir;
    private final Logger logger;
    private final Map<UUID, Data> cache = new HashMap<>();
    private final Set<UUID> unreadable = new HashSet<>();

    public VisitorStore(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "visitors");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + " — visitors will not persist.");
        }
    }

    private File fileFor(UUID player) {
        return new File(dir, player + ".yml");
    }

    /** A player's data, read from disk the first time. */
    public Data get(UUID player) {
        return cache.computeIfAbsent(player, this::read);
    }

    /** Whether this player's file could not be read, so nothing of theirs may change. */
    public boolean readOnly(UUID player) {
        get(player);
        return unreadable.contains(player);
    }

    private Data read(UUID player) {
        Data data = new Data();
        File file = fileFor(player);
        if (!file.exists()) {
            return data;
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

    /** Write one player now. */
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

    /** Write every player held in memory. */
    public void saveAll() {
        for (UUID player : Set.copyOf(cache.keySet())) {
            save(player);
        }
    }

    /** Save and forget a player who has left. */
    public void unload(UUID player) {
        save(player);
        cache.remove(player);
        unreadable.remove(player);
    }
}
