package com.mystipixel.royalgarden.garden;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A WorldEdit {@code .schem} (Sponge schematic, versions 2 and 3), read into blocks and marker signs.
 *
 * <p>Plain data, no server types: block states stay as strings until they are pasted, so a schematic can
 * be read — and tested — without a server.
 *
 * <h2>Markers</h2>
 *
 * <p>A sign whose first non-blank line is a name in square brackets — {@code [sam]}, {@code [spawn]},
 * {@code [window]}, {@code [composter]} — is a <b>marker</b>, not part of the build: it says where
 * something goes and which way it faces, and is left out when the schematic is pasted. The way a marker
 * faces is the way its writing faces: stand in front of the sign and read it, and whatever stands at the
 * marker will be looking at you. So a builder edits the spots in-world, in the same session as the build.
 */
public final class Schematic {

    /** A block with data beyond its state: a sign's text, a banner's patterns, a head's owner. */
    public record BlockEntity(int x, int y, int z, String id, Map<String, Object> data) {
    }

    /**
     * A marker, in schematic coordinates.
     *
     * @param yaw the way its writing faces, as a Minecraft yaw: 0 is +z, 90 is -x, 180 is -z, -90 is +x
     */
    public record Marker(String name, int x, int y, int z, float yaw) {
    }

    private static final Pattern MARKER = Pattern.compile("^\\s*\\[([a-z0-9_-]+)]\\s*$");

    /** Blocks a builder stands a build on — what the ground layer is found by. */
    private static final Set<String> GROUND = Set.of("minecraft:grass_block", "minecraft:dirt",
            "minecraft:coarse_dirt", "minecraft:podzol", "minecraft:rooted_dirt", "minecraft:dirt_path",
            "minecraft:stone", "minecraft:sand", "minecraft:gravel", "minecraft:mud", "minecraft:moss_block",
            "minecraft:farmland", "minecraft:mycelium");

    private final int width;
    private final int height;
    private final int length;
    private final String[] palette;
    private final int[] blocks;
    private final List<BlockEntity> blockEntities;
    private final Map<String, Marker> markers;

    private Schematic(int width, int height, int length, String[] palette, int[] blocks,
                      List<BlockEntity> blockEntities, Map<String, Marker> markers) {
        this.width = width;
        this.height = height;
        this.length = length;
        this.palette = palette;
        this.blocks = blocks;
        this.blockEntities = blockEntities;
        this.markers = markers;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int length() {
        return length;
    }

    /** The block state at a schematic position, e.g. {@code minecraft:oak_stairs[facing=north,...]}. */
    public String state(int x, int y, int z) {
        return palette[blocks[(y * length + z) * width + x]];
    }

    /** Block entities, markers left out. */
    public List<BlockEntity> blockEntities() {
        return blockEntities;
    }

    /** Markers by name. */
    public Map<String, Marker> markers() {
        return markers;
    }

    /** Whether a position holds a marker sign, which is pasted as air. */
    public boolean isMarker(int x, int y, int z) {
        for (Marker marker : markers.values()) {
            if (marker.x() == x && marker.y() == y && marker.z() == z) {
                return true;
            }
        }
        return false;
    }

    /**
     * The layer that lines up with the Garden's surface: the highest layer at least half made of ground
     * blocks. Everything below it is subsoil; everything above it stands on the surface. 0 when nothing
     * reads as ground — a build with no base sits directly on the surface.
     */
    public int groundLayer() {
        int found = -1;
        for (int y = 0; y < height; y++) {
            int ground = 0;
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    String state = state(x, y, z);
                    int bracket = state.indexOf('[');
                    if (GROUND.contains(bracket < 0 ? state : state.substring(0, bracket))) {
                        ground++;
                    }
                }
            }
            if (ground * 2 >= width * length) {
                found = y;
            }
        }
        return Math.max(0, found);
    }

    // ── reading ────────────────────────────────────────────────────────────────

    public static Schematic read(InputStream in) throws IOException {
        Map<String, Object> root = Nbt.read(in);
        Map<String, Object> nested = Nbt.compound(root, "Schematic");       // version 3 wraps everything
        if (nested != null) {
            root = nested;
        }
        int width = Nbt.integer(root, "Width", -1);
        int height = Nbt.integer(root, "Height", -1);
        int length = Nbt.integer(root, "Length", -1);
        if (width <= 0 || height <= 0 || length <= 0 || (long) width * height * length > 16_000_000L) {
            throw new IOException("Schematic size " + width + "x" + height + "x" + length + " is not usable.");
        }

        Map<String, Object> blockSection = Nbt.compound(root, "Blocks");     // version 3
        Map<String, Object> paletteTag;
        Object data;
        List<Object> entityTags;
        if (blockSection != null) {
            paletteTag = Nbt.compound(blockSection, "Palette");
            data = blockSection.get("Data");
            entityTags = Nbt.list(blockSection, "BlockEntities");
        } else {                                                              // version 2
            paletteTag = Nbt.compound(root, "Palette");
            data = root.get("BlockData");
            entityTags = Nbt.list(root, "BlockEntities");
        }
        if (paletteTag == null || !(data instanceof byte[] bytes)) {
            throw new IOException("Not a Sponge schematic: it has no block palette or block data. "
                    + "Save it with WorldEdit's //schem save.");
        }

        String[] palette = new String[paletteTag.size()];
        for (Map.Entry<String, Object> entry : paletteTag.entrySet()) {
            int index = entry.getValue() instanceof Number n ? n.intValue() : -1;
            if (index < 0 || index >= palette.length) {
                throw new IOException("Schematic palette index " + index + " is out of range; the file is damaged.");
            }
            palette[index] = entry.getKey();
        }
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == null) {
                palette[i] = "minecraft:air";
            }
        }

        int[] blocks = varints(bytes, width * height * length, palette.length);

        List<BlockEntity> entities = new ArrayList<>();
        Map<String, Marker> markers = new LinkedHashMap<>();
        for (Object raw : entityTags) {
            if (!(raw instanceof Map<?, ?>)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> tag = (Map<String, Object>) raw;
            int[] pos = tag.get("Pos") instanceof int[] p && p.length == 3 ? p : null;
            String id = Nbt.string(tag, "Id");
            if (pos == null || id == null || pos[0] < 0 || pos[0] >= width || pos[1] < 0 || pos[1] >= height
                    || pos[2] < 0 || pos[2] >= length) {
                continue;
            }
            Map<String, Object> entityData = Nbt.compound(tag, "Data");         // version 3
            if (entityData == null) {
                entityData = tag;                                               // version 2 is flat
            }
            String state = palette[blocks[(pos[1] * length + pos[2]) * width + pos[0]]];
            String name = markerName(id, entityData);
            if (name != null) {
                markers.put(name, new Marker(name, pos[0], pos[1], pos[2], signYaw(state)));
                continue;
            }
            entities.add(new BlockEntity(pos[0], pos[1], pos[2], id, entityData));
        }
        return new Schematic(width, height, length, palette, blocks, Collections.unmodifiableList(entities),
                Collections.unmodifiableMap(markers));
    }

    /** Sponge block data: one unsigned LEB128 varint per block. */
    private static int[] varints(byte[] bytes, int count, int paletteSize) throws IOException {
        int[] out = new int[count];
        int i = 0;
        int read = 0;
        while (read < count) {
            int value = 0;
            int shift = 0;
            while (true) {
                if (i >= bytes.length) {
                    throw new IOException("Schematic block data ends early; the file is damaged.");
                }
                int b = bytes[i++] & 0xFF;
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
                shift += 7;
                if (shift > 28) {
                    throw new IOException("Schematic block data has a bad number; the file is damaged.");
                }
            }
            if (value >= paletteSize) {
                throw new IOException("Schematic refers to a block it has no palette entry for; the file is damaged.");
            }
            out[read++] = value;
        }
        return out;
    }

    // ── markers ────────────────────────────────────────────────────────────────

    /** The marker a sign names, lower case, or null if it is not a sign or not a marker. */
    static String markerName(String id, Map<String, Object> data) {
        if (!id.endsWith("sign")) {
            return null;
        }
        for (String side : new String[]{"front_text", "back_text"}) {
            for (Object message : Nbt.list(Nbt.compound(data, side), "messages")) {
                String text = plain(message);
                if (text.isBlank()) {
                    continue;
                }
                Matcher matcher = MARKER.matcher(text.toLowerCase(Locale.ROOT));
                return matcher.matches() ? matcher.group(1) : null;   // the first line with anything on it decides
            }
        }
        return null;
    }

    /**
     * The plain text of one sign line, however it was stored: a bare string, a JSON string or component,
     * or a component as a compound.
     */
    static String plain(Object message) {
        if (message instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder();
            Object text = map.get("text");
            if (text != null) {
                out.append(text);
            }
            if (map.get("extra") instanceof List<?> extra) {
                for (Object part : extra) {
                    out.append(plain(part));
                }
            }
            return out.toString();
        }
        if (!(message instanceof String raw)) {
            return "";
        }
        String text = raw.trim();
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            return text.substring(1, text.length() - 1).replace("\\\"", "\"");
        }
        if (text.startsWith("{")) {
            // A JSON component. Only the text of it matters here, so pull every "text" value out in order.
            StringBuilder out = new StringBuilder();
            Matcher matcher = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
            while (matcher.find()) {
                out.append(matcher.group(1));
            }
            return out.toString();
        }
        return raw;
    }

    /**
     * The way a sign's writing faces, as a yaw. A standing sign's {@code rotation} counts sixteenths of a
     * turn from south; a wall sign faces away from its wall.
     */
    static float signYaw(String state) {
        Matcher rotation = Pattern.compile("rotation=(\\d+)").matcher(state);
        if (rotation.find()) {
            float yaw = Integer.parseInt(rotation.group(1)) * 22.5f;
            return yaw > 180 ? yaw - 360 : yaw;
        }
        Matcher facing = Pattern.compile("facing=(north|south|east|west)").matcher(state);
        if (facing.find()) {
            return switch (facing.group(1)) {
                case "north" -> 180f;
                case "east" -> -90f;
                case "west" -> 90f;
                default -> 0f;
            };
        }
        return 0f;
    }
}
