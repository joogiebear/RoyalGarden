package com.mystipixel.royalgarden.pest;

import com.mystipixel.royalgarden.visitor.VisitorMath;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * One kind of pest from {@code pests.yml}.
 *
 * @param command     the console command that spawns it, with {@code %x% %y% %z% %world%} and friends
 * @param mob         the EcoMobs id that command spawns, or blank to take whatever it spawned
 * @param weight      how likely it is to be the next pest, against every other eligible kind
 * @param gardenLevel the Garden level it starts appearing at
 * @param copper      Copper for killing one
 * @param gardenXp    Garden XP for killing one
 * @param description lines for the Pest Log
 * @param rewards     its own {@code rewards:} section, or null
 */
public record PestType(String id, String displayName, Material icon, String command, String mob, double weight,
                       int gardenLevel, VisitorMath.Range copper, long gardenXp, List<String> description,
                       ConfigurationSection rewards) {
}
