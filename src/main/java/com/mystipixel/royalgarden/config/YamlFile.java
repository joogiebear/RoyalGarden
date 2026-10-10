package com.mystipixel.royalgarden.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One editable YAML file in the extension's folder. The bundled copy is written out on first run and set
 * as defaults, so a key missing on disk reads as the shipped value.
 *
 * <p>Bundled files live under {@code royalgarden/} in the jar, not its root: the extension's class loader
 * asks the host first, and {@code getResource("config.yml")} would return RoyalSkyblock's own file.
 */
public class YamlFile {

    private static final String BUNDLE_DIR = "royalgarden/";

    private final File file;
    private final String name;
    private final boolean withDefaults;
    protected final Logger logger;
    private YamlConfiguration yaml = new YamlConfiguration();

    public YamlFile(File dataFolder, String name, Logger logger) {
        this(dataFolder, name, logger, true);
    }

    /**
     * @param withDefaults whether the shipped copy backs missing keys. Off for list-like files (the preset
     *                     catalogue), where a deleted entry would otherwise keep reappearing.
     */
    public YamlFile(File dataFolder, String name, Logger logger, boolean withDefaults) {
        this.file = new File(dataFolder, name);
        this.name = name;
        this.withDefaults = withDefaults;
        this.logger = logger;
        reload();
    }

    /**
     * Re-read the file from disk. A file that fails to parse is logged and left untouched, and the shipped
     * defaults are used until it is fixed.
     */
    public void reload() {
        if (!file.exists()) {
            saveBundled();
        }
        YamlConfiguration loaded = new YamlConfiguration();
        if (file.exists()) {
            try {
                loaded.load(file);
            } catch (IOException | InvalidConfigurationException broken) {
                logger.severe(name + " could not be read, so the shipped defaults are in use until it is "
                        + "fixed: " + broken.getMessage());
                loaded = new YamlConfiguration();
            }
        }
        YamlConfiguration defaults = withDefaults ? bundled() : null;
        if (defaults != null) {
            loaded.setDefaults(defaults);
        }
        this.yaml = loaded;
    }

    /** With the shipped copy behind it as defaults. */
    public YamlConfiguration yaml() {
        return yaml;
    }

    public File file() {
        return file;
    }

    private YamlConfiguration bundled() {
        try (InputStream in = YamlFile.class.getClassLoader().getResourceAsStream(BUNDLE_DIR + name)) {
            if (in == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            return null;
        }
    }

    private void saveBundled() {
        try (InputStream in = YamlFile.class.getClassLoader().getResourceAsStream(BUNDLE_DIR + name)) {
            if (in == null) {
                return;
            }
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                logger.warning("Could not create " + parent + " to write " + name + " into.");
                return;
            }
            Files.copy(in, file.toPath());
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write the default " + name, e);
        }
    }
}
