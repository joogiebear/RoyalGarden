package com.mystipixel.royalgarden.garden;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Just enough of Minecraft's NBT format to read a WorldEdit schematic.
 *
 * <p>Our own rather than a library: the format is ten tag types and a length prefix, and the one NBT
 * library on a Paper server arrives transitively, not as something the API promises. Reading only — a
 * schematic is made in WorldEdit and consumed here, never written.
 *
 * <p>Tags come back as plain Java: {@code Map<String, Object>} for a compound, {@code List<Object>} for a
 * list, {@code byte[]} / {@code int[]} / {@code long[]} for the arrays, and boxed numbers and strings for
 * the rest.
 */
public final class Nbt {

    private static final int MAX_DEPTH = 512;

    private Nbt() {
    }

    /** Read a file's root compound, gzipped or not. */
    public static Map<String, Object> read(InputStream raw) throws IOException {
        PushbackInputStream in = new PushbackInputStream(new BufferedInputStream(raw), 2);
        int first = in.read();
        int second = in.read();
        if (second >= 0) {
            in.unread(second);
        }
        if (first >= 0) {
            in.unread(first);
        }
        boolean gzipped = first == 0x1F && second == 0x8B;
        DataInputStream data = new DataInputStream(gzipped ? new GZIPInputStream(in) : in);

        int type = data.readUnsignedByte();
        if (type != 10) {
            throw new IOException("Not an NBT file: the root tag is type " + type + ", not a compound.");
        }
        data.readUTF();                                   // the root's name, unused
        return compound(data, 0);
    }

    private static Map<String, Object> compound(DataInputStream in, int depth) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            int type = in.readUnsignedByte();
            if (type == 0) {
                return out;
            }
            String name = in.readUTF();
            out.put(name, payload(in, type, depth + 1));
        }
    }

    private static Object payload(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("NBT nested deeper than " + MAX_DEPTH + "; the file is damaged.");
        }
        return switch (type) {
            case 1 -> in.readByte();
            case 2 -> in.readShort();
            case 3 -> in.readInt();
            case 4 -> in.readLong();
            case 5 -> in.readFloat();
            case 6 -> in.readDouble();
            case 7 -> {
                byte[] bytes = new byte[length(in)];
                in.readFully(bytes);
                yield bytes;
            }
            case 8 -> in.readUTF();
            case 9 -> {
                int elementType = in.readUnsignedByte();
                int size = length(in);
                List<Object> list = new ArrayList<>(Math.min(size, 4096));
                for (int i = 0; i < size; i++) {
                    list.add(payload(in, elementType, depth + 1));
                }
                yield list;
            }
            case 10 -> compound(in, depth);
            case 11 -> {
                int[] ints = new int[length(in)];
                for (int i = 0; i < ints.length; i++) {
                    ints[i] = in.readInt();
                }
                yield ints;
            }
            case 12 -> {
                long[] longs = new long[length(in)];
                for (int i = 0; i < longs.length; i++) {
                    longs[i] = in.readLong();
                }
                yield longs;
            }
            default -> throw new IOException("Unknown NBT tag type " + type + "; the file is damaged.");
        };
    }

    /** An array or list length, refused if negative or absurd rather than trusted into an allocation. */
    private static int length(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) {
            throw new IOException("NBT length " + length + " is out of range; the file is damaged.");
        }
        return length;
    }

    // ── reading values out ─────────────────────────────────────────────────────

    /** A compound child, or null if absent or not a compound. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    /** A list child, or an empty list if absent or not a list. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    /** A whole number child, or {@code fallback}. */
    public static int integer(Map<String, Object> parent, String key, int fallback) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** A string child, or null. */
    public static String string(Map<String, Object> parent, String key) {
        Object value = parent == null ? null : parent.get(key);
        return value instanceof String text ? text : null;
    }
}
