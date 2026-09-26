package com.mystipixel.royalgarden.garden;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchematicTest {

    /**
     * A 3x2x3 Sponge v3 schematic: a layer of grass, then air with a standing sign in the middle — a
     * [sam] marker whose writing faces east — and a plain sign in a corner.
     */
    private static byte[] sample(String markerLine) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            out.writeByte(10);
            out.writeUTF("Schematic");
            intTag(out, "Version", 3);
            shortTag(out, "Width", 3);
            shortTag(out, "Height", 2);
            shortTag(out, "Length", 3);

            out.writeByte(10);
            out.writeUTF("Blocks");
            out.writeByte(10);
            out.writeUTF("Palette");
            intTag(out, "minecraft:grass_block[snowy=false]", 0);
            intTag(out, "minecraft:air", 1);
            intTag(out, "minecraft:oak_sign[rotation=12,waterlogged=false]", 2);
            out.writeByte(0);                                          // end Palette

            byte[] data = new byte[18];
            for (int i = 0; i < 9; i++) {
                data[i] = 0;                                           // y=0: grass
            }
            for (int i = 9; i < 18; i++) {
                data[i] = 1;                                           // y=1: air…
            }
            data[9 + (1 * 3 + 1)] = 2;                                 // …with a sign at (1,1,1)
            data[9] = 2;                                               // …and one at (0,1,0)
            out.writeByte(7);
            out.writeUTF("Data");
            out.writeInt(data.length);
            out.write(data);

            out.writeByte(9);
            out.writeUTF("BlockEntities");
            out.writeByte(10);
            out.writeInt(2);
            sign(out, new int[]{1, 1, 1}, markerLine);
            sign(out, new int[]{0, 1, 0}, "\"Welcome\"");
            out.writeByte(0);                                          // end Blocks
            out.writeByte(0);                                          // end Schematic
            out.writeByte(0);                                          // end root
        }
        return bytes.toByteArray();
    }

    private static void sign(DataOutputStream out, int[] pos, String firstLine) throws IOException {
        out.writeByte(11);
        out.writeUTF("Pos");
        out.writeInt(3);
        for (int p : pos) {
            out.writeInt(p);
        }
        out.writeByte(8);
        out.writeUTF("Id");
        out.writeUTF("minecraft:sign");
        out.writeByte(10);
        out.writeUTF("Data");
        out.writeByte(10);
        out.writeUTF("front_text");
        out.writeByte(9);
        out.writeUTF("messages");
        out.writeByte(8);
        out.writeInt(4);
        out.writeUTF(firstLine);
        out.writeUTF("\"\"");
        out.writeUTF("\"\"");
        out.writeUTF("\"\"");
        out.writeByte(0);                                              // end front_text
        out.writeByte(0);                                              // end Data
        out.writeByte(0);                                              // end entity
    }

    private static void intTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(3);
        out.writeUTF(name);
        out.writeInt(value);
    }

    private static void shortTag(DataOutputStream out, String name, int value) throws IOException {
        out.writeByte(2);
        out.writeUTF(name);
        out.writeShort(value);
    }

    @Test
    void readsSizeBlocksAndGround() throws IOException {
        Schematic schematic = Schematic.read(new ByteArrayInputStream(sample("\"[sam]\"")));
        assertEquals(3, schematic.width());
        assertEquals(2, schematic.height());
        assertEquals(3, schematic.length());
        assertEquals("minecraft:grass_block[snowy=false]", schematic.state(2, 0, 2));
        assertEquals("minecraft:air", schematic.state(2, 1, 2));
        assertEquals(0, schematic.groundLayer());
    }

    @Test
    void aBracketedSignIsAMarkerNotABlockEntity() throws IOException {
        Schematic schematic = Schematic.read(new ByteArrayInputStream(sample("\"[Sam]\"")));
        Schematic.Marker sam = schematic.markers().get("sam");
        assertEquals(1, sam.x());
        assertEquals(1, sam.y());
        assertEquals(1, sam.z());
        assertEquals(-90f, sam.yaw());                     // rotation 12 is three quarters round: facing +x
        assertTrue(schematic.isMarker(1, 1, 1));
        assertEquals(1, schematic.blockEntities().size()); // only the plain sign is left to decorate
        assertEquals(0, schematic.blockEntities().getFirst().x());
    }

    @Test
    void markerTextIsReadHoweverTheLineWasStored() throws IOException {
        for (String stored : List.of("[window]", "\"[window]\"", "{\"text\":\"[window]\"}",
                "{\"text\":\"\",\"extra\":[{\"text\":\"[window]\"}]}")) {
            assertTrue(Schematic.read(new ByteArrayInputStream(sample(stored))).markers().containsKey("window"),
                    stored);
        }
        assertEquals("[spawn]", Schematic.plain(Map.of("text", "[spawn]")));
    }

    @Test
    void ordinarySignsAreNotMarkers() {
        assertNull(Schematic.markerName("minecraft:sign",
                Map.of("front_text", Map.of("messages", List.of("\"Welcome [home]\"")))));
        assertNull(Schematic.markerName("minecraft:barrel",
                Map.of("front_text", Map.of("messages", List.of("\"[sam]\"")))));
    }

    @Test
    void signsFaceTheWayTheirWritingDoes() {
        assertEquals(0f, Schematic.signYaw("minecraft:oak_sign[rotation=0]"));
        assertEquals(90f, Schematic.signYaw("minecraft:oak_sign[rotation=4]"));
        assertEquals(180f, Schematic.signYaw("minecraft:oak_sign[rotation=8]"));
        assertEquals(-90f, Schematic.signYaw("minecraft:oak_wall_sign[facing=east]"));
        assertEquals(180f, Schematic.signYaw("minecraft:oak_wall_sign[facing=north]"));
    }

    @Test
    void aFileThatIsNotASchematicIsRefused() {
        assertThrows(IOException.class, () -> Schematic.read(new ByteArrayInputStream(new byte[]{1, 2, 3})));
    }
}
