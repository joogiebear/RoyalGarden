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
 * What stands on the farmhouse plot: the built-in barn, or a WorldEdit schematic from {@code
 * farmhouse.schematic}, pasted centred with its ground layer on the surface. Marker signs ({@code [sam]},
 * {@code [spawn]}, {@code [window]}, {@code [composter]}) place things; spots set in-game with {@code
 * /garden farmhouse set} win over them. Container contents are never pasted, so a farmhouse cannot
 * duplicate items. A Garden built with a different {@link #id} is rebuilt on its next load.
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

    public static String generatedId() {
        return "generated-" + GardenBuilder.BARN_VERSION;
    }

    private static volatile Farmhouse current = new Farmhouse(null, "", 0, generatedId());

    public static final List<String> SPOTS = List.of("sam", "spawn", "window", "composter");

    // By name; these win over the schematic's signs.
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

    public static Farmhouse current() {
        return current;
    }

    /**
     * Reads {@code farmhouse:} and loads its schematic. Any problem is logged and the built-in barn used,
     * so a bad schematic costs a warning, never a Garden.
     */
    public static void configure(File dataFolder, ConfigurationSection section, Logger logger) {
        loadSpots(new File(dataFolder, "farmhouse-spots.yml"), logger);
        String name = section == null ? "" : section.getString("schematic", "").trim();
        if (name.isEmpty()) {
            current = new Farmhouse(null, "", 0, generatedId());
            return;
        }
        File file = new File(dataFolder, name);
        if (!file.exists()) {
            extractBundled(name, file, logger);
        }
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

    // Only fills a gap: a file already on disk, edited or not, is never overwritten.
    private static void extractBundled(String name, File target, Logger logger) {
        if (name.contains("/") || name.contains("\\")) {
            return;
        }
        try (InputStream in = Farmhouse.class.getClassLoader().getResourceAsStream("royalgarden/" + name)) {
            if (in != null) {
                Files.copy(in, target.toPath());
                logger.info("Farmhouse: wrote the bundled " + name + " to " + target + ".");
            }
        } catch (IOException | RuntimeException failed) {
            logger.warning("Farmhouse: could not write the bundled " + name + " (" + failed.getMessage() + ").");
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

    public boolean isSchematic() {
        return schematic != null;
    }

    /** For telling whether a Garden was built with this farmhouse. */
    public String id() {
        return id;
    }

    /** Blank for the built-in barn. */
    public String file() {
        return file;
    }

    /** So rebuilding knows how high to clear. */
    public int top() {
        return schematic == null ? GardenLayout.groundY() + 16
                : GardenLayout.groundY() + schematic.height() - groundLayer;
    }

    /** Set in-game, else the schematic's marker, else null (the built-in barn's own). */
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

    public static Map<String, Anchor> setSpots() {
        return setSpots;
    }

    /** Null forgets it. Stored in {@code farmhouse-spots.yml}. */
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

    /** Expects bare ground: a new Garden, or a centre plot just cleared for a rebuild. */
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

    private final Map<String, Integer> indices = new HashMap<>();
    private final List<BlockData> byIndex = new ArrayList<>();

    // Only after parsedPalette has run.
    private int paletteIndex(int x, int y, int z) {
        return indices.get(schematic.state(x, y, z));
    }

    // A state this version does not know becomes air, logged once.
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

    // Returns true if anything was applied.
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
