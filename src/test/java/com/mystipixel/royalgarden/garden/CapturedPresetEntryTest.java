package com.mystipixel.royalgarden.garden;

import com.mystipixel.royalgarden.config.Messages;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CapturedPresetEntryTest {

    @TempDir
    Path data;

    // a messages.yml from before the captured-preset keys still gives the text the code used to write
    @Test
    void aFirstCaptureWritesTheShippedNameAndLore() throws IOException {
        Files.writeString(data.resolve("messages.yml"), "prefix: \"\"\n");
        Logger logger = Logger.getLogger("test");
        PresetLibrary library = new PresetLibrary(data.toFile(), new Messages(data.toFile(), logger), logger);

        File source = data.resolve("source.preset").toFile();
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("royalgarden/presets/wheat.preset")) {
            assertNotNull(in, "wheat.preset is not on the classpath");
            Files.copy(in, source.toPath());
        }
        library.save("cocoa", PlotTemplate.load(source));

        YamlConfiguration index = YamlConfiguration.loadConfiguration(data.resolve("presets.yml").toFile());
        assertEquals("&acocoa", index.getString("presets.cocoa.display-name"));
        assertEquals(List.of("&7A captured farm."), index.getStringList("presets.cocoa.lore"));
        assertEquals("wheat", index.getString("presets.cocoa.icon"));
    }
}
