package com.mystipixel.royalgarden.config;

import com.mystipixel.royalskyblock.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.logging.Logger;

/**
 * Every line a player can read, from {@code messages.yml}, using {@code &} and {@code &#rrggbb} colours
 * like the host. Placeholders come in pairs: {@code send(player, "unlocked", "%plot%", "B2")}. An empty
 * message is switched off and never sent.
 */
public final class Messages extends YamlFile {

    public Messages(File dataFolder, Logger logger) {
        super(dataFolder, "messages.yml", logger);
    }

    /** Placeholders filled, or the key itself if the message is missing. */
    public String raw(String key, String... placeholders) {
        String text = yaml().getString(key);
        if (text == null) {
            return key;
        }
        return fill(text, placeholders);
    }

    public Component get(String key, String... placeholders) {
        return Text.color(raw(key, placeholders));
    }

    /** Adds the configured prefix. Does nothing for an empty message. */
    public void send(CommandSender to, String key, String... placeholders) {
        String text = raw(key, placeholders);
        if (text.isEmpty()) {
            return;
        }
        to.sendMessage(Text.color(yaml().getString("prefix", "") + text));
    }

    /** For refusals per swing, which would flood chat. */
    public void actionBar(Player to, String key, String... placeholders) {
        String text = raw(key, placeholders);
        if (text.isEmpty()) {
            return;
        }
        to.sendActionBar(Text.color(text));
    }

    /** Odd trailing arguments are ignored. */
    public static String fill(String text, String... placeholders) {
        String out = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1];
            out = out.replace(placeholders[i], value == null ? "" : value);
        }
        return out;
    }
}
