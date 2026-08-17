package com.mystipixel.royalgarden.garden;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The named plot presets: farms built once, stamped onto plots on demand.
 *
 * <p>A preset is a {@link PlotTemplate} with an id. One of them is the <b>default</b>, which is what a
 * newly generated plot contains; the rest are applied deliberately, either by an admin or by a player
 * choosing one for a plot they have unlocked. "Flat" is the absence of a preset rather than a preset
 * of its own, so there is exactly one code path that means "plain field".
 *
 * <h2>Loading</h2>
 *
 * <p>Presets are read from disk on first use and then cached. A tall preset is roughly three quarters
 * of a megabyte in memory, and a server with a full set of twenty-four would hold about eighteen — fine
 * to keep, wasteful to load for a server that only ever uses two. Caching on demand splits the
 * difference without needing to know which case this is.
 */
public final class PresetLibrary {

    /** Ids are file names, so they are restricted to something that cannot escape the directory. */
    private static final String ID_PATTERN = "[a-z0-9_-]{1,32}";

    private final File dir;
    private final File index;
    private final Logger logger;
    private final Map<String, PlotTemplate> cache = new HashMap<>();

    /** Id of the preset new plots are generated from, or null for a plain field. */
    private String defaultId;

    public PresetLibrary(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "presets");
        this.index = new File(dataFolder, "presets.yml");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + " — plot presets will not persist.");
        }
        this.defaultId = YamlConfiguration.loadConfiguration(index).getString("default");
        if (defaultId != null && !exists(defaultId)) {
            logger.warning("Default preset '" + defaultId + "' is missing; new plots will be flat.");
            this.defaultId = null;
        }
    }

    /** Whether a string is usable as a preset id. */
    public static boolean validId(String id) {
        return id != null && id.toLowerCase(Locale.ROOT).matches(ID_PATTERN);
    }

    private File fileFor(String id) {
        return new File(dir, id + ".preset");
    }

    public boolean exists(String id) {
        return validId(id) && fileFor(id).isFile();
    }

    /** Every preset id on disk, alphabetically. */
    public List<String> ids() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".preset"));
        if (files != null) {
            for (File file : files) {
                out.add(file.getName().substring(0, file.getName().length() - ".preset".length()));
            }
        }
        out.sort(String::compareTo);
        return out;
    }

    /**
     * A preset by id, or null if it is missing or unreadable.
     *
     * <p>An unreadable preset returns null and logs rather than throwing: the caller's fallback is a
     * flat plot, which is a worse plot but not a broken server, and a preset that fails to parse is an
     * admin's problem to fix rather than a player's to be disconnected by.
     */
    public PlotTemplate get(String id) {
        if (!validId(id)) {
            return null;
        }
        PlotTemplate cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        try {
            PlotTemplate loaded = PlotTemplate.load(fileFor(id));
            if (loaded != null) {
                cache.put(id, loaded);
            }
            return loaded;
        } catch (IOException bad) {
            logger.severe("Preset '" + id + "' could not be read: " + bad.getMessage());
            return null;
        }
    }

    /** Save a captured plot under an id, replacing any preset already there. */
    public void save(String id, PlotTemplate preset) throws IOException {
        if (!validId(id)) {
            throw new IOException("'" + id + "' is not a valid preset name (a-z, 0-9, _ and - only).");
        }
        preset.save(fileFor(id));
        cache.put(id, preset);
    }

    /** Delete a preset. Clears the default if that is what it was. */
    public boolean delete(String id) {
        if (!exists(id)) {
            return false;
        }
        cache.remove(id);
        boolean deleted = fileFor(id).delete();
        if (deleted && id.equals(defaultId)) {
            setDefault(null);
        }
        return deleted;
    }

    /** The preset new plots are built from, or null for a flat field. */
    public String defaultId() {
        return defaultId;
    }

    public PlotTemplate defaultPreset() {
        return defaultId == null ? null : get(defaultId);
    }

    /** Set (or clear, with null) the preset new plots are built from. */
    public void setDefault(String id) {
        this.defaultId = id;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("default", id);
        try {
            yaml.save(index);
        } catch (IOException e) {
            logger.severe("Could not save the default preset: " + e.getMessage());
        }
    }
}
