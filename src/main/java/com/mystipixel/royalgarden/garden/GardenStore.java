package com.mystipixel.royalgarden.garden;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where unlock state lives: one small YAML per Garden, in the extension's own data folder.
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
 * <p>State is cached per owner for the life of the server; there is at most one Garden per player and
 * they are tiny.
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

    /** A player's unlock state, read from disk the first time and cached after. */
    public GardenState get(UUID owner) {
        return cache.computeIfAbsent(owner, this::read);
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
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        List<String> plots = yaml.getStringList("unlocked");
        for (String entry : plots) {
            String[] parts = entry.split(",");
            if (parts.length != 2) {
                logger.warning("Garden " + owner + ": ignoring malformed plot '" + entry + "'.");
                continue;
            }
            try {
                state.restore(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
            } catch (NumberFormatException notAPlot) {
                logger.warning("Garden " + owner + ": ignoring malformed plot '" + entry + "'.");
            }
        }
        return state;
    }

    /**
     * Write a Garden's unlocks to disk.
     *
     * <p>Called on every unlock rather than on a timer or at shutdown. One unlock is one small file
     * write, and the alternative is a crash costing a player a plot they watched themselves buy.
     */
    public void save(GardenState state) {
        YamlConfiguration yaml = new YamlConfiguration();
        List<String> plots = new java.util.ArrayList<>();
        for (int[] plot : state.plots()) {
            plots.add(plot[0] + "," + plot[1]);
        }
        yaml.set("unlocked", plots);
        try {
            yaml.save(fileFor(state.owner()));
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save Garden state for " + state.owner(), e);
        }
    }
}
