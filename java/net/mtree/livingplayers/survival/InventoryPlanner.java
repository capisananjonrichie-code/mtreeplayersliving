package net.mtree.livingplayers.survival;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Decides what a bot may discard when its inventory fills. Valuable items are never on the list. Pure. */
public final class InventoryPlanner {
    private InventoryPlanner() {}

    /** Hard keep-limits for junk; anything not listed here is never discarded. */
    private static final Map<String, Integer> JUNK_LIMIT = Map.of("DIRT", 32, "GRAVEL", 0, "SAND", 16, "GRASS_BLOCK", 0,
        "COBBLESTONE", 128, "ANDESITE", 0, "DIORITE", 0, "GRANITE", 0, "DEEPSLATE", 0, "COBBLED_DEEPSLATE", 64);

    private static final Set<String> NEVER = Set.of("DIAMOND", "NETHERITE_INGOT", "ENCHANTED_GOLDEN_APPLE", "ELYTRA", "TOTEM_OF_UNDYING");

    public static Map<String, Integer> surplus(Map<String, Integer> have) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : have.entrySet()) {
            if (NEVER.contains(e.getKey()) || e.getKey().contains("DIAMOND") || e.getKey().contains("NETHERITE")) continue;
            Integer keep = JUNK_LIMIT.get(e.getKey());
            if (keep != null && e.getValue() > keep) out.put(e.getKey(), e.getValue() - keep);
        }
        return out;
    }
}
