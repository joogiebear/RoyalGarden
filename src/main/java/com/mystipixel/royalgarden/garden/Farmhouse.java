package com.mystipixel.royalgarden.garden;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.Gson;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Banner;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DecoratedPot;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.configuration.ConfigurationSection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * What stands on the farmhouse plot: the built-in barn, or a WorldEdit schematic from
 * {@code farmhouse.schematic} in {@code config.yml}.
 *
 * <p>A schematic is pasted centred on the plot, its ground layer ({@link Schematic#groundLayer}) on the
 * Garden's surface. Its marker signs say where things go — {@code [sam]}, {@code [spawn]},
 * {@code [window]} (the front of the visitors' line) and {@code [composter]} — and are pasted as air. A
 * spot with no marker falls back to where the built-in barn puts it, which on a different building may
 * well be inside a wall, so a schematic without them is warned about.
 *
 * <h2>What is pasted</h2>
 *
 * <p>Block states, and the data that only decorates: sign text, banner patterns, the owner of a head,
 * a decorated pot's sherds. Never container contents — the same rule presets keep, so a farmhouse can
 * never be a way to duplicate items. A chest in the schematic is an empty chest.
 *
 * <h2>Spots set in-game</h2>
 *
 * <p>{@code /garden farmhouse set <spot>} records a spot where an admin is standing, in
 * {@code farmhouse-spots.yml}. Those win over the schematic's signs, so a farmhouse can be arranged
 * without WorldEdit — and they apply to the built-in barn too. Every Garden's farmhouse plot is laid out
 * the same, so one set of world coordinates serves them all.
 *
 * <h2>Changing it</h2>
 *
 * <p>{@link #id} fingerprints the farmhouse in use. Each Garden records the one it was built with, and
 * one built with another is rebuilt on its next load — see {@link GardenWorlds#prepare}.
 */
public final class Farmhouse {

    /** A marked spot in world coordinates, facing {@code yaw}. */
    public record Anchor(double x, double y, double z, float yaw) {
        public Location at(World world) {
            return new Location(world, x, y, z, yaw, 0f);
        }

        public Block block(World world) {
            return world.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
    }

    /** The id of the built-in barn at its current version. */
    public static String generatedId() {
        return "generated-" + GardenBuilder.BARN_VERSION;
    }

    private static volatile Farmhouse current = new Farmhouse(null, "", 0, generatedId());

    /** The spots anything can be told to stand at. */
    public static final List<String> SPOTS = List.of("sam", "spawn", "window", "composter");

    /** Spots set in-game, by name; they win over the schematic's signs. */
    private static volatile Map<String, Anchor> setSpots = Map.of();
    private static File spotsFile;

    private final Schematic schematic;
    private final String file;
    private final int groundLayer;
    private final String id;
    private BlockData[] parsed;

    private Farmhouse(Schematic schematic, String file, int groundLayer, String id) {
        this.schematic = schematic;
        this.file = file;
        this.groundLayer = groundLayer;
        this.id = id;
    }

    /** The farmhouse in use. */
    public static Farmhouse current() {
        return current;
    }

    /**
     * Read {@code farmhouse:} from {@code config.yml} and load its schematic, if it names one. Any problem
     * with the file is logged and the built-in barn used instead — a bad schematic costs a warning, never
     * a Garden.
     */
    public static void configure(File dataFolder, ConfigurationSection section, Logger logger) {
        loadSpots(new File(dataFolder, "farmhouse-spots.yml"), logger);
        String name = section == null ? "" : section.getString("schematic", "").trim();
        if (name.isEmpty()) {
            current = new Farmhouse(null, "", 0, generatedId());
            return;
        }
        File file = new File(dataFolder, name);
        if (!file.isFile()) {
            logger.warning("farmhouse.schematic: " + file + " does not exist; using the built-in barn.");
            current = new Farmhouse(null, "", 0, generatedId());
            return;
        }
        try (InputStream in = new FileInputStream(file)) {
            Schematic schematic = Schematic.read(in);
            String layerSetting = section.getString("ground-layer", "auto").trim();
            int ground = layerSetting.equalsIgnoreCase("auto") ? schematic.groundLayer()
                    : Math.clamp(Integer.parseInt(layerSetting), 0, schematic.height() - 1);
            if (schematic.width() > GardenLayout.plotSize() || schematic.length() > GardenLayout.plotSize()) {
                logger.warning("farmhouse.schematic: " + name + " is " + schematic.width() + "x" + schematic.length()
                        + ", wider than a plot (" + GardenLayout.plotSize() + "); using the built-in barn.");
                current = new Farmhouse(null, "", 0, generatedId());
                return;
            }
            String id = "schematic-" + fingerprint(file) + "-" + ground;
            current = new Farmhouse(schematic, name, ground, id);
            logger.info("Farmhouse: " + name + " (" + schematic.width() + "x" + schematic.height() + "x"
                    + schematic.length() + ", ground at layer " + ground + ", markers "
                    + schematic.markers().keySet() + ").");
            for (String wanted : SPOTS) {
                if (!schematic.markers().containsKey(wanted) && !setSpots.containsKey(wanted)) {
                    logger.warning("Farmhouse: " + name + " has no [" + wanted + "] sign; "
                            + fallback(wanted) + " Stand where it should go and run /garden farmhouse set "
                            + wanted + ", or put a sign reading [" + wanted + "] there in the schematic.");
                }
            }
        } catch (IOException | RuntimeException unusable) {
            logger.warning("farmhouse.schematic: " + name + " could not be read (" + unusable.getMessage()
                    + "); using the built-in barn.");
            current = new Farmhouse(null, "", 0, generatedId());
        }
    }

    private static String fallback(String marker) {
        return switch (marker) {
            case "composter" -> "the Composter block will not be placed (the desk still opens it).";
            case "window" -> "visitors queue where visitors.yml's line: says.";
            default -> "it stays where the built-in barn has it, which may be inside this building.";
        };
    }

    private static String fingerprint(File file) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            return HexFormat.of().formatHex(sha.digest(Files.readAllBytes(file.toPath()))).substring(0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            return Long.toHexString(file.length()) + Long.toHexString(file.lastModified());
        }
    }

    /** Whether a schematic is in use rather than the built-in barn. */
    public boolean isSchematic() {
        return schematic != null;
    }

    /** Which farmhouse this is, for telling whether a Garden was built with it. */
    public String id() {
        return id;
    }

    /** The schematic file's name, blank for the built-in barn. */
    public String file() {
        return file;
    }

    /** The highest Y the farmhouse reaches, so rebuilding knows how high to clear. */
    public int top() {
        return schematic == null ? GardenLayout.groundY() + 16
                : GardenLayout.groundY() + schematic.height() - groundLayer;
    }

    /** A spot: set in-game, else the schematic's marker, else null (the built-in barn's own). */
    public Anchor anchor(String name) {
        Anchor set = setSpots.get(name);
        if (set != null) {
            return set;
        }
        if (schematic == null) {
            return null;
        }
        Schematic.Marker marker = schematic.markers().get(name);
        if (marker == null) {
            return null;
        }
        return new Anchor(minX() + marker.x() + 0.5, worldY(marker.y()), minZ() + marker.z() + 0.5, marker.yaw());
    }

    // ── spots set in-game ──────────────────────────────────────────────────────

    private static void loadSpots(File file, Logger logger) {
        spotsFile = file;
        Map<String, Anchor> loaded = new HashMap<>();
        if (file.isFile()) {
            org.bukkit.configuration.file.YamlConfiguration yaml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            for (String name : yaml.getKeys(false)) {
                String raw = yaml.getString(name, "");
                String[] parts = raw.split(",");
                try {
                    if (parts.length != 4) {
                        throw new NumberFormatException();
                    }
                    loaded.put(name.toLowerCase(Locale.ROOT), new Anchor(Double.parseDouble(parts[0].trim()),
                            Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim()),
                            Float.parseFloat(parts[3].trim())));
                } catch (NumberFormatException bad) {
                    logger.warning("farmhouse-spots.yml: " + name + ": '" + raw + "' should be 'x, y, z, yaw'.");
                }
            }
        }
        setSpots = Map.copyOf(loaded);
    }

    /** The spots set in-game. */
    public static Map<String, Anchor> setSpots() {
        return setSpots;
    }

    /** Record a spot (or forget it, with null) in {@code farmhouse-spots.yml}. */
    public static void setSpot(String name, Anchor anchor) throws IOException {
        Map<String, Anchor> next = new HashMap<>(setSpots);
        if (anchor == null) {
            next.remove(name);
        } else {
            next.put(name, anchor);
        }
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Farmhouse spots set in-game with /garden farmhouse set <spot>: x, y, z, yaw.",
                "They win over a schematic's [sam] / [spawn] / [window] / [composter] signs."));
        for (String spot : SPOTS) {
            Anchor at = next.get(spot);
            if (at != null) {
                yaml.set(spot, String.format(Locale.ROOT, "%.2f, %.2f, %.2f, %.1f", at.x(), at.y(), at.z(), at.yaw()));
            }
        }
        GardenStore.writeAtomically(spotsFile.toPath(), yaml.saveToString());
        setSpots = Map.copyOf(next);
    }

    private int minX() {
        return -(schematic.width() / 2);
    }

    private int minZ() {
        return -(schematic.length() / 2);
    }

    private int worldY(int schematicY) {
        return GardenLayout.groundY() + schematicY - groundLayer;
    }

    // ── building ───────────────────────────────────────────────────────────────

    /**
     * Put the farmhouse on the centre plot. The plot is expected to be bare ground: a new Garden, or one
     * whose centre plot has just been cleared for a rebuild.
     */
    public void build(World world, Logger logger) {
        if (schematic == null) {
            GardenBuilder.buildBarn(world);
            return;
        }
        long started = System.nanoTime();
        BlockData[] states = parsedPalette(logger);
        int minX = minX();
        int minZ = minZ();
        BlockData air = Bukkit.createBlockData(Material.AIR);
        for (int y = 0; y < schematic.height(); y++) {
            for (int z = 0; z < schematic.length(); z++) {
                for (int x = 0; x < schematic.width(); x++) {
                    BlockData data = schematic.isMarker(x, y, z) ? air : states[paletteIndex(x, y, z)];
                    world.getBlockAt(minX + x, worldY(y), minZ + z).setBlockData(data, false);
                }
            }
        }
        int decorated = 0;
        for (Schematic.BlockEntity entity : schematic.blockEntities()) {
            try {
                if (decorate(world.getBlockAt(minX + entity.x(), worldY(entity.y()), minZ + entity.z()), entity)) {
                    decorated++;
                }
            } catch (RuntimeException skipped) {
                logger.fine("Farmhouse: could not decorate " + entity.id() + ": " + skipped);
            }
        }
        logger.info("Garden '" + world.getName() + "': built the farmhouse from " + file + " ("
                + decorated + " decorated blocks) in " + (System.nanoTime() - started) / 1_000_000 + "ms.");
    }

    /** Distinct states in the order {@link #parsedPalette} parsed them, and where each one is. */
    private final Map<String, Integer> indices = new HashMap<>();
    private final List<BlockData> byIndex = new ArrayList<>();

    /** Where a position's state is in {@link #parsed}. Only after {@link #parsedPalette} has run. */
    private int paletteIndex(int x, int y, int z) {
        return indices.get(schematic.state(x, y, z));
    }

    /** Every distinct state parsed once; one this version does not know becomes air, logged once. */
    private synchronized BlockData[] parsedPalette(Logger logger) {
        if (parsed != null) {
            return parsed;
        }
        indices.clear();
        byIndex.clear();
        BlockData air = Bukkit.createBlockData(Material.AIR);
        for (int y = 0; y < schematic.height(); y++) {
            for (int z = 0; z < schematic.length(); z++) {
                for (int x = 0; x < schematic.width(); x++) {
                    String state = schematic.state(x, y, z);
                    if (indices.containsKey(state)) {
                        continue;
                    }
                    BlockData data;
                    try {
                        data = Bukkit.createBlockData(state);
                    } catch (IllegalArgumentException unknown) {
                        logger.warning("Farmhouse: '" + state + "' does not exist on this version; pasted as air.");
                        data = air;
                    }
                    indices.put(state, byIndex.size());
                    byIndex.add(data);
                }
            }
        }
        parsed = byIndex.toArray(new BlockData[0]);
        return parsed;
    }

    /** Apply the decorative part of a block entity. Returns true if anything was applied. */
    private static boolean decorate(Block block, Schematic.BlockEntity entity) {
        BlockState state = block.getState();
        boolean changed = switch (state) {
            case Sign sign -> sign(sign, entity.data());
            case Banner banner -> banner(banner, entity.data());
            case Skull skull -> skull(skull, entity.data());
            case DecoratedPot pot -> pot(pot, entity.data());
            default -> false;
        };
        if (changed) {
            state.update(true, false);
        }
        return changed;
    }

    private static final Gson GSON = new Gson();

    private static boolean sign(Sign sign, Map<String, Object> data) {
        boolean any = false;
        for (Side side : Side.values()) {
            Map<String, Object> text = Nbt.compound(data, side == Side.FRONT ? "front_text" : "back_text");
            if (text == null) {
                continue;
            }
            SignSide target = sign.getSide(side);
            List<Object> messages = Nbt.list(text, "messages");
            for (int line = 0; line < Math.min(4, messages.size()); line++) {
                Component component = component(messages.get(line));
                if (component != null) {
                    target.line(line, component);
                    any = true;
                }
            }
            String color = Nbt.string(text, "color");
            if (color != null) {
                try {
                    target.setColor(DyeColor.valueOf(color.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException notAColour) {
                    // leave the default
                }
            }
            if (text.get("has_glowing_text") instanceof Number glowing) {
                target.setGlowingText(glowing.intValue() != 0);
            }
        }
        return any;
    }

    /** A sign line as a component, from any of the ways it may have been stored. */
    private static Component component(Object message) {
        try {
            if (message instanceof Map<?, ?> map) {
                return GsonComponentSerializer.gson().deserialize(GSON.toJson(map));
            }
            if (message instanceof String raw) {
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                    return null;
                }
                if (trimmed.startsWith("{") || trimmed.startsWith("\"") || trimmed.startsWith("[")) {
                    return GsonComponentSerializer.gson().deserialize(trimmed);
                }
                return Component.text(raw);
            }
        } catch (RuntimeException notJson) {
            return Component.text(Schematic.plain(message));
        }
        return null;
    }

    private static boolean banner(Banner banner, Map<String, Object> data) {
        List<Pattern> patterns = new ArrayList<>();
        for (Object raw : Nbt.list(data, "patterns")) {
            if (!(raw instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> entry = (Map<String, Object>) raw;
            String pattern = Nbt.string(entry, "pattern");
            String color = Nbt.string(entry, "color");
            NamespacedKey key = pattern == null ? null : NamespacedKey.fromString(pattern);
            PatternType type = key == null ? null : Registry.BANNER_PATTERN.get(key);
            if (type == null || color == null) {
                continue;
            }
            try {
                patterns.add(new Pattern(DyeColor.valueOf(color.toUpperCase(Locale.ROOT)), type));
            } catch (IllegalArgumentException notAColour) {
                // skip that layer
            }
        }
        if (patterns.isEmpty()) {
            return false;
        }
        banner.setPatterns(patterns);
        return true;
    }

    private static boolean skull(Skull skull, Map<String, Object> data) {
        Object raw = data.get("profile");
        if (raw instanceof String name && !name.isBlank()) {
            skull.setPlayerProfile(Bukkit.createProfile(name));
            return true;
        }
        if (!(raw instanceof Map<?, ?>)) {
            return false;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> profileTag = (Map<String, Object>) raw;
        UUID id = profileTag.get("id") instanceof int[] ints && ints.length == 4
                ? new UUID((long) ints[0] << 32 | (ints[1] & 0xFFFFFFFFL), (long) ints[2] << 32 | (ints[3] & 0xFFFFFFFFL))
                : UUID.randomUUID();
        PlayerProfile profile = Bukkit.createProfile(id, Nbt.string(profileTag, "name"));
        for (Object property : Nbt.list(profileTag, "properties")) {
            if (property instanceof Map<?, ?> map && map.get("name") instanceof String name
                    && map.get("value") instanceof String value) {
                profile.setProperty(new ProfileProperty(name, value,
                        map.get("signature") instanceof String signature ? signature : null));
            }
        }
        skull.setPlayerProfile(profile);
        return true;
    }

    private static boolean pot(DecoratedPot pot, Map<String, Object> data) {
        List<Object> sherds = Nbt.list(data, "sherds");
        DecoratedPot.Side[] order = {DecoratedPot.Side.BACK, DecoratedPot.Side.LEFT, DecoratedPot.Side.RIGHT,
                DecoratedPot.Side.FRONT};
        boolean any = false;
        for (int i = 0; i < Math.min(order.length, sherds.size()); i++) {
            if (sherds.get(i) instanceof String id) {
                Material material = Material.matchMaterial(id);
                if (material != null) {
                    pot.setSherd(order[i], material);
                    any = true;
                }
            }
        }
        return any;
    }
}
