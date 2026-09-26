package com.mystipixel.royalgarden.visitor;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;

import java.util.List;

/**
 * One visitor from {@code visitors.yml}: who they are, how often they come, and what they may ask for.
 *
 * @param rarity      the id of the rarity in {@code rarities:}
 * @param weight      how likely they are to be the next arrival, against every other eligible visitor
 * @param gardenLevel the Garden level they start visiting at
 * @param permission  a node the player must have for them to visit, or blank for none
 * @param entity      the entity type they are drawn as, lower case ({@code villager}, {@code piglin}…)
 * @param profession  a villager profession, for villagers; blank for none
 * @param biome       a villager type (the biome look), for villagers; blank for plains
 * @param dialogue    lines shown on their offer
 * @param requests    the requests they choose between, one per visit
 * @param rewards     their own {@code rewards:} section, or null
 */
public record VisitorType(String id, String displayName, String rarity, double weight, int gardenLevel,
                          String permission, Material icon, String entity, String profession, String biome,
                          List<String> dialogue, List<Request> requests, ConfigurationSection rewards) {

    /** One set of items a visitor may ask for, chosen by {@code weight}. */
    public record Request(double weight, List<Item> items) {
    }

    /**
     * One requested item.
     *
     * @param item   an eco item lookup: {@code wheat}, {@code ecoitems:enchanted_wheat}…
     * @param amount how many, before scaling
     * @param name   a display name to show instead of the item's own, or blank
     */
    public record Item(String item, VisitorMath.Range amount, String name) {
    }
}
