package com.mystipixel.royalgarden.garden;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One plot captured as blocks, to be stamped onto other plots. Gzipped: magic, version, dimensions,
 * base offset from {@link GardenLayout#groundY()}, a palette of {@link BlockData#getAsString()} strings,
 * and one palette index per block. A state this version does not know is pasted as air and logged once.
 * Only block states are captured, never contents, so a preset cannot duplicate items.
 */
public final class PlotTemplate {

    private static final int MAGIC = 0x52475054;      // "RGPT"
    private static final int VERSION = 1;

    // The floor course, relative to GardenLayout.groundY().
    private static final int BASE_OFFSET = -3;

    // Hard ceiling on a template read from disk, whatever the config says: the height of a world.
    private static final int ABSOLUTE_MAX_HEIGHT = 384;

    /** What to do about harvestable blocks when pasting. */
    public record PasteRules(boolean resetCropAge, Set<Material> neverPaste) {
        public static final PasteRules AS_CAPTURED = new PasteRules(false, Set.of());
    }

    private final int size;
    private final int height;
    private final String[] palette;
    private final short[] blocks;

    // One parse per palette entry, not per block.
    private final BlockData[] parsed;

    // The parsed states after PasteRules are applied, for the rules they were applied with.
    private BlockData[] ruled;
    private PasteRules ruledFor;

    private PlotTemplate(int size, int height, String[] palette, short[] blocks) {
        this.size = size;
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
        return size * height;
    }

    private int index(int x, int y, int z) {
        return (x * height + y) * size + z;
    }

    /**
     * Captured height stops at the highest non-air block, so a flat field costs four layers to stamp, not
     * forty-eight.
     */
    public static PlotTemplate capture(World world, int px, int pz, int maxHeight) throws IOException {
        int size = GardenLayout.plotSize();
        int baseY = GardenLayout.groundY() + BASE_OFFSET;
        int minX = GardenLayout.plotMin(px);
        int minZ = GardenLayout.plotMin(pz);

        int top = baseY;
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                for (int y = baseY + maxHeight - 1; y > top; y--) {
                    if (!world.getBlockAt(minX + x, y, minZ + z).getType().isAir()) {
                        top = y;
                        break;
                    }
                }
            }
        }
        int height = Math.max(4, top - baseY + 1);

        List<String> palette = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        short[] blocks = new short[size * height * size];

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < size; z++) {
                    String state = world.getBlockAt(minX + x, baseY + y, minZ + z)
                            .getBlockData().getAsString();
                    Integer id = seen.get(state);
                    if (id == null) {
                        id = palette.size();
                        if (id > 0xFFFF) {
                            throw new IOException("This plot has more than 65,536 distinct block states "
                                    + "and cannot be stored as a template.");
                        }
                        palette.add(state);
                        seen.put(state, id);
                    }
                    blocks[(x * height + y) * size + z] = (short) (int) id;
                }
            }
        }
        return new PlotTemplate(size, height, palette.toArray(new String[0]), blocks);
    }

    /**
     * Stamps one x-row and returns the blocks written. A whole plot is too much for one tick, so the caller
     * spends a block budget per tick and comes back for the next row.
     */
    public int pasteRow(World world, int px, int pz, int row, PasteRules rules) {
        int baseY = GardenLayout.groundY() + BASE_OFFSET;
        int x = GardenLayout.plotMin(px) + row;
        int minZ = GardenLayout.plotMin(pz);
        BlockData[] states = ruled(rules);

        for (int y = 0; y < height; y++) {
            for (int z = 0; z < size; z++) {
                BlockData data = states[blocks[index(row, y, z)] & 0xFFFF];
                Block block = world.getBlockAt(x, baseY + y, minZ + z);
                block.setBlockData(data, false);
            }
        }
        return rowCost();
    }

    // Built once per set of rules.
    private synchronized BlockData[] ruled(PasteRules rules) {
        if (ruled != null && rules.equals(ruledFor)) {
            return ruled;
        }
        BlockData[] out = new BlockData[palette.length];
        BlockData air = Bukkit.createBlockData(Material.AIR);
        for (int i = 0; i < palette.length; i++) {
            BlockData data = parsed(i);
            if (rules.neverPaste().contains(data.getMaterial())) {
                data = air;
            } else if (rules.resetCropAge() && data instanceof Ageable ageable && ageable.getAge() > 0) {
                Ageable young = (Ageable) ageable.clone();
                young.setAge(0);
                data = young;
            }
            out[i] = data;
        }
        this.ruled = out;
        this.ruledFor = rules;
        return out;
    }

    // Air if this version does not know the state.
    private BlockData parsed(int id) {
        BlockData cached = parsed[id];
        if (cached != null) {
            return cached;
        }
        try {
            cached = Bukkit.createBlockData(palette[id]);
        } catch (IllegalArgumentException unknownState) {
            Bukkit.getLogger().warning("[RoyalGarden] Template block '" + palette[id]
                    + "' does not exist on this version; it will be pasted as air.");
            cached = Bukkit.createBlockData(Material.AIR);
        }
        parsed[id] = cached;
        return cached;
    }

    /** Via a temporary file, so a crash mid-write cannot leave a truncated template. */
    public void save(File file) throws IOException {
        Path target = file.toPath();
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try (OutputStream raw = Files.newOutputStream(temp);
             DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(raw)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(size);
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
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Null if none is saved; throws if it is unusable. Every header number is checked here so a damaged file
     * fails once, by name, not mid-paste on the main thread.
     */
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
            if (size != GardenLayout.plotSize()) {
                // Plot size is baked into the data; stamping into a differently-sized plot would leave a seam.
                throw new IOException("Plot template is " + size + " wide, but plots are now "
                        + GardenLayout.plotSize() + ". Recapture it.");
            }
            int height = in.readInt();
            if (height < 1 || height > ABSOLUTE_MAX_HEIGHT) {
                throw new IOException("Plot template claims a height of " + height + "; it is damaged.");
            }
            in.readInt();                                   // base offset, fixed for this version
            int paletteSize = in.readInt();
            if (paletteSize < 1 || paletteSize > 0x10000) {
                throw new IOException("Plot template claims " + paletteSize + " block states; it is damaged.");
            }
            String[] palette = new String[paletteSize];
            for (int i = 0; i < paletteSize; i++) {
                palette[i] = in.readUTF();
            }
            short[] blocks = new short[size * height * size];
            for (int i = 0; i < blocks.length; i++) {
                blocks[i] = in.readShort();
                if ((blocks[i] & 0xFFFF) >= paletteSize) {
                    throw new IOException("Plot template refers to a block state it does not have; "
                            + "it is damaged.");
                }
            }
            return new PlotTemplate(size, height, palette, blocks);
        }
    }
}
