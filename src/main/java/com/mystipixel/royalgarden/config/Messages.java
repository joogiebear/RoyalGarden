package com.mystipixel.royalgarden.config;

import com.mystipixel.royalskyblock.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.logging.Logger;

/**
 * Every line a player can read, from {@code messages.yml}.
 *
 * <p>Messages use {@code &} colour codes (and {@code &#rrggbb} hex) through RoyalSkyblock's own
 * {@link Text} helper, so they are written exactly the way the host's messages are. Placeholders are
 * {@code %name%} and are passed as pairs: {@code send(player, "unlocked", "%plot%", "B2")}.
 *
 * <p>An empty message is a message switched off: {@code send} skips it rather than sending a blank
 * line, which is how an admin silences something without a code change.
 */
public final class Messages extends YamlFile {

    public Messages(File dataFolder, Logger logger) {
        super(dataFolder, "messages.yml", logger);
    }

    /** The raw text of a message with placeholders filled, or the key itself if it is missing. */
    public String raw(String key, String... placeholders) {
        String text = yaml().getString(key);
        if (text == null) {
            return key;
        }
        return fill(text, placeholders);
    }

    /** A message as a component, placeholders filled. */
    public Component get(String key, String... placeholders) {
        return Text.color(raw(key, placeholders));
    }

    /** Send a chat message with the configured prefix. Does nothing for an empty message. */
    public void send(CommandSender to, String key, String... placeholders) {
        String text = raw(key, placeholders);
        if (text.isEmpty()) {
            return;
        }
        to.sendMessage(Text.color(yaml().getString("prefix", "") + text));
    }

    /** Show a message in the action bar, where a refusal per swing does not flood chat. */
    public void actionBar(Player to, String key, String... placeholders) {
        String text = raw(key, placeholders);
        if (text.isEmpty()) {
            return;
        }
        to.sendActionBar(Text.color(text));
    }

    /** Replace {@code %name%} pairs in a string. Odd trailing arguments are ignored. */
    public static String fill(String text, String... placeholders) {
        String out = text;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            String value = placeholders[i + 1];
            out = out.replace(placeholders[i], value == null ? "" : value);
        }
        return out;
    }
}
