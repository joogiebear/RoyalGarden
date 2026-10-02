package com.mystipixel.royalgarden.garden;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundledPresetsTest {

    @TempDir
    Path dir;

    @Test
    void everyBundledPresetLoadsAndHasACatalogueEntry() throws IOException {
        String catalogue;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("royalgarden/presets.yml")) {
            assertNotNull(in, "presets.yml is not on the classpath");
            catalogue = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String id : PresetLibrary.BUNDLED) {
            assertTrue(PresetLibrary.validId(id), id + " is not a valid preset id");
            assertTrue(catalogue.contains("\n  " + id + ":\n"), "presets.yml has no entry for " + id);
            File file = dir.resolve(id + ".preset").toFile();
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("royalgarden/presets/" + id + ".preset")) {
                assertNotNull(in, id + ".preset is not on the classpath");
                Files.copy(in, file.toPath());
            }
            PlotTemplate template = PlotTemplate.load(file);
            assertNotNull(template, id);
            assertEquals(5, template.height(), id);
        }
    }
}
