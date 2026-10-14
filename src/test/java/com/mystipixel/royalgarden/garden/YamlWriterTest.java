package com.mystipixel.royalgarden.garden;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlWriterTest {

    @TempDir
    Path dir;

    private final Logger logger = Logger.getLogger("YamlWriterTest");

    @Test
    void queuedTextIsReadBeforeItLandsAndWrittenByClose() throws Exception {
        YamlWriter writer = new YamlWriter(logger);
        File file = dir.resolve("a.yml").toFile();
        for (int i = 1; i <= 50; i++) {
            writer.write(file.toPath(), "count: " + i + "\n");
        }
        assertTrue(writer.exists(file));
        YamlConfiguration yaml = new YamlConfiguration();
        writer.load(yaml, file);
        assertEquals(50, yaml.getInt("count"), "a read sees the newest text, queued or written");

        writer.close();
        assertEquals("count: 50\n", Files.readString(file.toPath()));
        assertNull(writer.pending(file.toPath()));
    }

    @Test
    void writeNowWinsOverOlderQueuedText() throws Exception {
        YamlWriter writer = new YamlWriter(logger);
        Path file = dir.resolve("b.yml");
        writer.write(file, "value: old\n");
        writer.writeNow(file, "value: new\n");
        assertEquals("value: new\n", Files.readString(file));

        writer.close();
        assertEquals("value: new\n", Files.readString(file), "the older queued text never lands after it");
    }

    @Test
    void writesAfterCloseHappenInline() throws Exception {
        YamlWriter writer = new YamlWriter(logger);
        writer.close();
        Path file = dir.resolve("c.yml");
        writer.write(file, "late: true\n");
        assertEquals("late: true\n", Files.readString(file));
    }

    @Test
    void failureCallbackRunsWhenTheFileCannotBeWritten() {
        YamlWriter writer = new YamlWriter(logger);
        Path file = dir.resolve("missing-dir").resolve("d.yml");
        AtomicBoolean failed = new AtomicBoolean();
        writer.write(file, "x: 1\n", () -> failed.set(true));
        writer.close();
        assertTrue(failed.get());
        assertFalse(Files.exists(file));
        assertFalse(writer.exists(file.toFile()));
    }
}
