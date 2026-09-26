package com.mystipixel.royalgarden.upgrade;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectTemplateTest {

    private static final Map<String, List<String>> LISTS = Map.of(
            "%blocks%", List.of("wheat"), "%drops%", List.of("wheat", "hay_block"));
    private static final Map<String, String> TEXT = Map.of("%level%", "3", "%crop%", "wheat");

    @Test
    void fillsTextAndSpreadsLists() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("multiplier", 2);
        args.put("chance", "%level% * 5");
        args.put("on_items", "%drops%");
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("blocks", "%blocks%");
        filters.put("player_placed", false);
        Map<String, Object> effect = new LinkedHashMap<>();
        effect.put("id", "multiply_drops");
        effect.put("args", args);
        effect.put("triggers", List.of("block_item_drop"));
        effect.put("filters", filters);

        @SuppressWarnings("unchecked")
        Map<String, Object> filled = (Map<String, Object>) EffectTemplate.fill(effect, LISTS, TEXT);
        @SuppressWarnings("unchecked")
        Map<String, Object> filledArgs = (Map<String, Object>) filled.get("args");
        @SuppressWarnings("unchecked")
        Map<String, Object> filledFilters = (Map<String, Object>) filled.get("filters");

        assertEquals("3 * 5", filledArgs.get("chance"));
        assertEquals(2, filledArgs.get("multiplier"));
        assertEquals(List.of("wheat", "hay_block"), filledArgs.get("on_items"));
        assertEquals(List.of("wheat"), filledFilters.get("blocks"));
        assertEquals(false, filledFilters.get("player_placed"));
        assertEquals(List.of("block_item_drop"), filled.get("triggers"));
        assertEquals("%level% * 5", args.get("chance"));        // the template is not changed
    }

    @Test
    void listVariablesSpreadInsideLists() {
        assertEquals(List.of("melon", "wheat", "hay_block"),
                EffectTemplate.fill(List.of("melon", "%drops%"), LISTS, TEXT));
    }

    @Test
    void textInsideLongerStringsIsReplaced() {
        assertEquals("upgrade wheat to 3", EffectTemplate.fill("upgrade %crop% to %level%", LISTS, TEXT));
        assertEquals("%blocks% here", EffectTemplate.fill("%blocks% here", LISTS, TEXT));   // not whole: left
    }
}
