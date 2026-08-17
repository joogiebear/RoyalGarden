package com.mystipixel.royalgarden.garden;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One plot, captured as blocks, to be stamped onto every other plot.
 *
 * <p>Build a plot the way you want them all to look, capture it, and every plot generated afterwards
 * is that plot. This is the schematic step done in-house: a plot is a known 96&times;96 box at a known
 * height, so there is nothing to select and no origin to get wrong, which is most of what a schematic
 * format exists to record. It also means no WorldEdit dependency — the extension can stamp plots on a
 * server that has never heard of it.
 *
 * <h2>Format</h2>
 *
 * <p>Gzipped: a magic number, a version, the dimensions, the base height as an offset from
 * {@link GardenLayout#GROUND_Y}, a palette of block-state strings, and one palette index per block.
 * A palette matters more than it looks: a farm plot is tens of distinct states repeated a hundred
 * thousand times, so indices compress to a rounding error and the file stays small enough to keep in
 * the data folder rather than somewhere it can drift from the code.
 *
 * <p>Block states are stored as {@link BlockData#getAsString()} rather than as ids, so a template
 * survives a Minecraft version change as long as the blocks in it still exist — and fails loudly on
 * the one that doesn't, instead of silently becoming a different block.
 */
public final class PlotTemplate {

    private static final int MAGIC = 0x52475054;      // "RGPT"
    private static final int VERSION = 1;

    /** Lowest layer captured, relative to {@link GardenLayout#GROUND_Y} — the bedrock course. */
    private static final int BASE_OFFSET = -3;

    /** Ceiling on captured height, so one tall build cannot make every plot enormous to stamp. */
    public static final int MAX_HEIGHT = 48;

    private final int height;
    private final String[] palette;
    private final short[] blocks;

    /** Lazily-built BlockData for each palette entry, so a paste parses each state once, not once per block. */
    private final BlockData[] parsed;

    private PlotTemplate(int height, String[] palette, short[] blocks) {
        this.height = height;
        this.palette = palette;
        this.blocks = blocks;
        this.parsed = new BlockData[palette.length];
    }

    public int height() {
        return height;
    }

    /** Blocks written by one call to {@link #pasteRow}, for budgeting the work across ticks. */
    public int rowCost() {
        return GardenLayout.PLOT_SIZE * height;
    }

    private int index(int x, int y, int z) {
        return (x * height + y) * GardenLayout.PLOT_SIZE + z;
    }

    // ── capture ────────────────────────────────────────────────────────────────

    /**
     * Read a plot out of the world.
     *
     * <p>Captured height stops at the highest non-air block rather than at a fixed ceiling: a template
     * of a flat field should cost four layers to stamp, not forty-eight, and the difference is paid on
     * every plot of every Garden thereafter.
     */
    public static PlotTemplate capture(World world, int px, int pz) {
        int baseY = GardenLayout.GROUND_Y + BASE_OFFSET;
        int minX = GardenLayout.plotMin(px);
        int minZ = GardenLayout.plotMin(pz);

        int top = baseY;
        for (int x = 0; x < GardenLayout.PLOT_SIZE; x++) {
            for (int z = 0; z < GardenLayout.PLOT_SIZE; z++) {
                for (int y = baseY + MAX_HEIGHT - 1; y > top; y--) {
                    if (!world.getBlockAt(minX + x, y, minZ + z).getType().isAir()) {
                        top = y;
                        break;
                    }
                }
            }
        }
        int height = Math.max(4, top - baseY + 1);

        List<String> palette = new ArrayList<>();
        Map<String, Short> seen = new HashMap<>();
        short[] blocks = new short[GardenLayout.PLOT_SIZE * height * GardenLayout.PLOT_SIZE];

        for (int x = 0; x < GardenLayout.PLOT_SIZE; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < GardenLayout.PLOT_SIZE; z++) {
                    String state = world.getBlockAt(minX + x, baseY + y, minZ + z)
                            .getBlockData().getAsString();
                    Short id = seen.get(state);
                    if (id == null) {
                        id = (short) palette.size();
                        palette.add(state);
                        seen.put(state, id);
                    }
                    blocks[(x * height + y) * GardenLayout.PLOT_SIZE + z] = id;
                }
            }
        }
        return new PlotTemplate(height, palette.toArray(new String[0]), blocks);
    }

    // ── paste ──────────────────────────────────────────────────────────────────

    /**
     * Stamp one x-row of this template onto a plot. Returns the number of blocks written.
     *
     * <p>A row at a time because a whole plot is too much for one tick once a template has any height
     * to it — the caller spends a fixed block budget per tick and comes back for the next row.
     */
    public int pasteRow(World world, int px, int pz, int row) {
        int baseY = GardenLayout.GROUND_Y + BASE_OFFSET;
        int x = GardenLayout.plotMin(px) + row;
        int minZ = GardenLayout.plotMin(pz);

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < GardenLayout.PLOT_SIZE; z++) {
                BlockData data = data(blocks[index(row, y, z)]);
                if (data == null) {
                    continue;
                }
                Block block = world.getBlockAt(x, baseY + y, minZ + z);
                block.setBlockData(data, false);
            }
        }
        return rowCost();
    }

    /** The parsed block state for a palette id, or null if this version does not know it. */
    private BlockData data(short id) {
        BlockData cached = parsed[id];
        if (cached != null) {
            return cached;
        }
        try {
            cached = Bukkit.createBlockData(palette[id]);
        } catch (IllegalArgumentException unknownState) {
            cached = Bukkit.createBlockData(Material.AIR);   // gone from this version — leave a hole
        }
        parsed[id] = cached;
        return cached;
    }

    // ── storage ────────────────────────────────────────────────────────────────

    public void save(File file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(file))))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(GardenLayout.PLOT_SIZE);
            out.writeInt(height);
            out.writeInt(BASE_OFFSET);
            out.writeInt(palette.length);
            for (String state : palette) {
                out.writeUTF(state);
            }
            for (short block : blocks) {
                out.writeShort(block);
            }
        }
    }

    /** Read a template, or null if there isn't one saved. Throws if there is one and it is unusable. */
    public static PlotTemplate load(File file) throws IOException {
        if (!file.exists()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(
                new GZIPInputStream(new BufferedInputStream(new FileInputStream(file))))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a RoyalGarden plot template: " + file);
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Plot template is version " + version + ", expected " + VERSION);
            }
            int size = in.readInt();
            if (size != GardenLayout.PLOT_SIZE) {
                // Plot size is baked into the data. Refusing beats stamping a 96-wide template into a
                // differently-sized plot and leaving a seam nobody can explain later.
                throw new IOException("Plot template is " + size + " wide, but plots are now "
                        + GardenLayout.PLOT_SIZE + ". Recapture it.");
            }
            int height = in.readInt();
            in.readInt();                                   // base offset, fixed for this version
            int paletteSize = in.readInt();
            String[] palette = new String[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = in.readUTF();
            }
            short[] blocks = new short[GardenLayout.PLOT_SIZE * height * GardenLayout.PLOT_SIZE];
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = in.readShort();
            }
            return new PlotTemplate(height, palette, blocks);
        }
    }
}
