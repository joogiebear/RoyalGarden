package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.Durations;
import com.mystipixel.royalgarden.config.GardenConfig;
import com.mystipixel.royalgarden.config.YamlFile;
import com.mystipixel.royalskyblock.currency.Cost;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
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
 * <p>Two halves. The <b>templates</b> are captured blocks, one {@code .preset} file each under
 * {@code presets/}. The <b>catalogue</b> is {@code presets.yml}, which says which of them players may
 * choose from the desk and on what terms — name, icon, cost, cooldown, permission. A template with no
 * catalogue entry is an admin tool only; a catalogue entry with no template is skipped with a warning.
 *
 * <p>{@code flat} and {@code bedrock} are generated rather than captured. They need no file, cannot be
 * deleted, and can still be listed in the catalogue to offer them to players.
 *
 * <p>{@code presets.yml} also holds {@code default:}, the preset new Gardens generate their plots from.
 *
 * <h2>Loading</h2>
 *
 * <p>Templates are read from disk on first use and then cached. A tall one is roughly three quarters of
 * a megabyte in memory — fine to keep, wasteful to load for a server that only ever uses two.
 */
public final class PresetLibrary {

    /** Ids are file names, so they are restricted to something that cannot escape the directory. */
    private static final String ID_PATTERN = "[a-z0-9_-]{1,32}";

    /** One preset as players see it at the desk. */
    public record Entry(String id, String displayName, Material icon, List<String> lore, Cost cost,
                        long cooldownMillis, String permission, boolean selectable) {
    }

    private final File dir;
    private final YamlFile index;
    private final Logger logger;
    private final Map<String, PlotTemplate> cache = new HashMap<>();

    public PresetLibrary(File dataFolder, Logger logger) {
        this.dir = new File(dataFolder, "presets");
        this.logger = logger;
        if (!dir.exists() && !dir.mkdirs()) {
            logger.warning("Could not create " + dir + " — plot presets will not persist.");
        }
        this.index = new YamlFile(dataFolder, "presets.yml", logger, false);
        checkDefault();
    }

    /** Re-read the catalogue, and drop cached templates so recaptured files are picked up. */
    public void reload() {
        index.reload();
        cache.clear();
        checkDefault();
    }

    private void checkDefault() {
        String id = defaultId();
        if (id != null && !exists(id)) {
            logger.warning("Default preset '" + id + "' is missing; new plots will be flat.");
        }
    }

    /**
     * Whether a string is usable as a captured preset's id.
     *
     * <p>{@code flat} and {@code bedrock} are refused: they name plots that are generated rather than
     * captured, and a captured preset sharing one of those names would shadow a state a plot must
     * always be able to return to.
     */
    public static boolean validId(String id) {
        if (id == null) {
            return false;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.matches(ID_PATTERN) && !GardenWorlds.isBuiltIn(lower);
    }

    private File fileFor(String id) {
        return new File(dir, id + ".preset");
    }

    public boolean exists(String id) {
        return validId(id) && fileFor(id).isFile();
    }

    /** Whether an id can be applied: a captured preset on disk, or one of the two built-ins. */
    public boolean applicable(String id) {
        return GardenWorlds.isBuiltIn(id) || exists(id);
    }

    /** Every captured preset id on disk, alphabetically. */
    public List<String> ids() {
        List<String> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(".preset"));
        if (files != null) {
            for (File file : files) {
                String id = file.getName().substring(0, file.getName().length() - ".preset".length());
                if (validId(id)) {
                    out.add(id);
                }
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

    /**
     * Save a captured plot under an id, replacing any preset already there.
     *
     * <p>A first capture also writes a catalogue entry with {@code selectable: false}, so the new preset
     * shows up in {@code presets.yml} ready to be named, priced and switched on.
     */
    public void save(String id, PlotTemplate preset) throws IOException {
        if (!validId(id)) {
            throw new IOException("'" + id + "' is not a valid preset name (a-z, 0-9, _ and - only).");
        }
        preset.save(fileFor(id));
        cache.put(id, preset);
        if (!index.yaml().isSet("presets." + id)) {
            editIndex(yaml -> {
                String path = "presets." + id;
                yaml.set(path + ".display-name", "&a" + id);
                yaml.set(path + ".icon", "wheat");
                yaml.set(path + ".lore", List.of("&7A captured farm."));
                yaml.set(path + ".cost", "0 coins");
                yaml.set(path + ".selectable", false);
            });
        }
    }

    /** Delete a preset. Clears the default if that is what it was. */
    public boolean delete(String id) {
        if (!exists(id)) {
            return false;
        }
        cache.remove(id);
        boolean deleted = fileFor(id).delete();
        if (deleted && id.equals(defaultId())) {
            setDefault(null);
        }
        return deleted;
    }

    /** The preset new plots are built from, or null for a flat field. */
    public String defaultId() {
        String id = index.yaml().getString("default", "");
        return id == null || id.isBlank() || GardenWorlds.isBuiltIn(id) ? null : id.toLowerCase(Locale.ROOT);
    }

    public PlotTemplate defaultPreset() {
        String id = defaultId();
        return id == null ? null : get(id);
    }

    /** Set (or clear, with null) the preset new plots are built from. */
    public void setDefault(String id) {
        editIndex(yaml -> yaml.set("default", id == null ? "" : id));
    }

    // ── the catalogue ──────────────────────────────────────────────────────────

    /** A catalogue entry, or null if the id is not in {@code presets.yml}. */
    public Entry entry(String id) {
        if (id == null) {
            return null;
        }
        ConfigurationSection section = index.yaml().getConfigurationSection("presets." + id.toLowerCase(Locale.ROOT));
        if (section == null) {
            return null;
        }
        Material icon = Material.matchMaterial(section.getString("icon", "wheat"));
        String cooldown = section.getString("cooldown");
        return new Entry(
                id.toLowerCase(Locale.ROOT),
                section.getString("display-name", id),
                icon == null || !icon.isItem() ? Material.WHEAT : icon,
                section.getStringList("lore"),
                GardenConfig.parseCost(section, "cost", logger),
                cooldown == null ? -1L : Durations.parseMillis(cooldown),
                section.getString("permission", ""),
                section.getBoolean("selectable", true));
    }

    /** The presets players may pick at the desk, in the order {@code presets.yml} lists them. */
    public List<Entry> selectable() {
        List<Entry> out = new ArrayList<>();
        ConfigurationSection section = index.yaml().getConfigurationSection("presets");
        if (section == null) {
            return out;
        }
        for (String id : section.getKeys(false)) {
            Entry entry = entry(id);
            if (entry == null || !entry.selectable()) {
                continue;
            }
            if (!applicable(entry.id())) {
                logger.warning("presets.yml lists '" + id + "', but no such preset has been captured. "
                        + "Capture it with /garden capture " + id + ", or remove the entry.");
                continue;
            }
            out.add(entry);
        }
        return out;
    }

    private interface Edit {
        void apply(YamlConfiguration yaml);
    }

    /**
     * Change {@code presets.yml} on disk and reload it.
     *
     * <p>Read without the shipped defaults behind it, so writing the file back does not bake every
     * example entry into it — and refuse to write at all if the file does not parse, rather than
     * replacing the admin's catalogue with the one line being changed.
     */
    private void editIndex(Edit edit) {
        YamlConfiguration raw = new YamlConfiguration();
        File file = index.file();
        if (file.exists()) {
            try {
                raw.load(file);
            } catch (IOException | InvalidConfigurationException broken) {
                logger.severe("presets.yml does not parse, so it was not changed: " + broken.getMessage());
                return;
            }
        }
        edit.apply(raw);
        try {
            GardenStore.writeAtomically(file.toPath(), raw.saveToString());
        } catch (IOException e) {
            logger.severe("Could not save presets.yml: " + e.getMessage());
        }
        index.reload();
    }
}
