package com.mystipixel.royalgarden.upgrade;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fills an effects: template for one crop and level. The template is copied, never changed. A string
 * that is exactly a list variable ("%blocks%") becomes that list; text variables are replaced in strings.
 */
public final class EffectTemplate {

    private EffectTemplate() {
    }

    public static Object fill(Object node, Map<String, List<String>> lists, Map<String, String> text) {
        if (node instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), fill(entry.getValue(), lists, text));
            }
            return out;
        }
        if (node instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                Object filled = fill(item, lists, text);
                // "%blocks%" inside a list spreads into it rather than nesting a list.
                if (item instanceof String string && lists.containsKey(string.trim()) && filled instanceof List<?> spread) {
                    out.addAll(spread);
                } else {
                    out.add(filled);
                }
            }
            return out;
        }
        if (node instanceof String string) {
            List<String> whole = lists.get(string.trim());
            if (whole != null) {
                return new ArrayList<>(whole);
            }
            String out = string;
            for (Map.Entry<String, String> entry : text.entrySet()) {
                out = out.replace(entry.getKey(), entry.getValue());
            }
            return out;
        }
        return node;
    }
}
