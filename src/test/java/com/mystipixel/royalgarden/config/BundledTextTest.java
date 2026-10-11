package com.mystipixel.royalgarden.config;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Text read without a code fallback must ship in the bundled files: YamlFile sets them as defaults, so a
// server file that predates a key still shows the shipped text.
class BundledTextTest {

    private static final Path SOURCES = Path.of("src", "main", "java");
    private static final List<String> FILES = List.of("config.yml", "messages.yml", "composter.yml", "levels.yml",
            "milestones.yml", "pests.yml", "upgrades.yml", "visitors.yml", "presets.yml");
    private static final Pattern MESSAGE_CALL =
            Pattern.compile("\\bmessages(?:\\(\\))?\\s*\\.\\s*(raw|get|send|actionBar)\\s*\\(");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern MENU_STRING = Pattern.compile("\\bstring\\(\"([^\"]+)\"\\)");

    @Test
    void textReadWithoutAFallbackShips() throws IOException {
        Map<String, List<String>> lines = Map.of(
                "config.yml", List.of("menu.title", "preset-menu.title", "sam.name", "clearing.progress-bar.filled",
                        "clearing.progress-bar.empty"),
                "composter.yml", List.of("menu.title", "progress-bar.filled", "progress-bar.empty"),
                "levels.yml", List.of("progress-bar.filled", "progress-bar.empty"),
                "milestones.yml", List.of("menu.title", "menu.entry.name", "menu.entry-maxed.name",
                        "progress-bar.filled", "progress-bar.empty"),
                "upgrades.yml", List.of("menu.title", "menu.filler", "menu.entry.name", "menu.entry-maxed.name"));
        for (Map.Entry<String, List<String>> file : lines.entrySet()) {
            YamlConfiguration yaml = load(file.getKey());
            for (String key : file.getValue()) {
                assertTrue(yaml.isString(key), file.getKey() + " ships " + key);
            }
        }
        for (String key : List.of("menu.entry.lore", "menu.entry-maxed.lore")) {
            assertTrue(load("milestones.yml").isList(key), "milestones.yml ships " + key);
            assertTrue(load("upgrades.yml").isList(key), "upgrades.yml ships " + key);
        }
        assertTrue(load("visitors.yml").isList("menu.offer.item.lore"), "visitors.yml ships menu.offer.item.lore");
        assertTrue(load("config.yml").isInt("clearing.progress-bar.length"), "config.yml ships the bar length");

        // the menus' string("path") helper reads menu.<path>
        for (String[] menu : new String[][]{{"visitor/VisitorMenu.java", "visitors.yml"}, {"pest/PestMenu.java", "pests.yml"}}) {
            YamlConfiguration yaml = load(menu[1]);
            Matcher m = MENU_STRING.matcher(Files.readString(SOURCES.resolve("com/mystipixel/royalgarden/" + menu[0])));
            int found = 0;
            while (m.find()) {
                found++;
                assertTrue(yaml.isString("menu." + m.group(1)), menu[1] + " ships menu." + m.group(1));
            }
            assertTrue(found >= 5, menu[0] + ": found only " + found + " string(...) reads");
        }
    }

    @Test
    void everyMessageKeyInCodeShipsInMessagesYml() throws IOException {
        YamlConfiguration shipped = load("messages.yml");
        List<String> problems = new ArrayList<>();
        int found = 0;
        List<Path> files;
        try (Stream<Path> walk = Files.walk(SOURCES)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
        for (Path file : files) {
            String source = Files.readString(file);
            Matcher m = MESSAGE_CALL.matcher(source);
            while (m.find()) {
                List<String> args = arguments(source, m.end());
                String keyArg = args.get(m.group(1).equals("send") || m.group(1).equals("actionBar") ? 1 : 0);
                Matcher literal = LITERAL.matcher(keyArg);
                while (literal.find()) {
                    found++;
                    String key = literal.group(1);
                    // "composter-stopped-" + reason: some key must start with the prefix
                    boolean shipsKey = key.endsWith("-")
                            ? shipped.getKeys(true).stream().anyMatch(k -> k.startsWith(key) && shipped.isString(k))
                            : shipped.isString(key);
                    if (!shipsKey) {
                        problems.add(file.getFileName() + ":" + lineOf(source, m.start()) + ": " + literal.group(1)
                                + " is not in messages.yml");
                    }
                }
            }
        }
        assertTrue(found > 50, "found only " + found + " message keys; is the scan still matching?");
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void bundledFilesHaveNoDuplicateKeys() throws IOException {
        for (String file : FILES) {
            assertEquals(List.of(), duplicates(resource(file)), "duplicate keys in " + file);
        }
    }

    @Test
    void clearingBarDefaultsDrawTheBarAsBefore() throws IOException {
        YamlConfiguration config = load("config.yml");
        assertEquals(20, config.getInt("clearing.progress-bar.length"));
        assertEquals("&a|", config.getString("clearing.progress-bar.filled"));
        assertEquals("&7|", config.getString("clearing.progress-bar.empty"));
    }

    // YAML keeps only the last of two equal keys, silently dropping the first
    private static List<String> duplicates(String yaml) {
        List<String> duplicates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        int items = 0;
        for (String line : yaml.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (trimmed.startsWith("-")) {
                // each list entry is its own map
                while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                    indents.remove(indents.size() - 1);
                    path.remove(path.size() - 1);
                }
                path.add("[" + items++ + "]");
                indents.add(indent);
                trimmed = trimmed.substring(1).strip();
                indent += 2;
            }
            int colon = trimmed.indexOf(':');
            if (colon < 0 || trimmed.startsWith("\"") || trimmed.startsWith("'")) {
                continue;
            }
            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            path.add(trimmed.substring(0, colon));
            indents.add(indent);
            String key = String.join(".", path);
            if (!seen.add(key)) {
                duplicates.add(key);
            }
        }
        return duplicates;
    }

    // top-level arguments of the call whose '(' ends just before start
    private static List<String> arguments(String source, int start) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (source.charAt(end) != '"') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                current.append(source, i, end + 1);
                i = end;
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    args.add(current.toString().strip());
                    return args;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                args.add(current.toString().strip());
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        throw new IllegalStateException("unclosed call");
    }

    private static int lineOf(String source, int index) {
        return (int) source.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = BundledTextTest.class.getClassLoader().getResourceAsStream("royalgarden/" + name)) {
            assertNotNull(in, name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static YamlConfiguration load(String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(resource(name));
        } catch (InvalidConfigurationException e) {
            throw new IOException(e);
        }
        return yaml;
    }
}
