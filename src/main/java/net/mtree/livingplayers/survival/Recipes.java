package net.mtree.livingplayers.survival;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * A small curated recipe set with exact vanilla ingredient counts. Crafting is pure arithmetic over item counts:
 * ingredients are CONSUMED and results ADDED, so nothing can be created from nothing (tested). Ingredient groups
 * ("LOG", "PLANKS") match any wood type.
 */
public final class Recipes {
    private Recipes() {}

    public record Ingredient(String item, int count) {}

    public record Recipe(String id, String result, int resultCount, List<Ingredient> ingredients, boolean needsTable) {}

    private static Recipe r(String id, String result, int n, boolean table, Object... ing) {
        List<Ingredient> l = new ArrayList<>();
        for (int i = 0; i < ing.length; i += 2) l.add(new Ingredient((String) ing[i], (Integer) ing[i + 1]));
        return new Recipe(id, result, n, List.copyOf(l), table);
    }

    public static final List<Recipe> ALL = List.of(
        r("PLANKS", "PLANKS", 4, false, "LOG", 1),
        r("STICK", "STICK", 4, false, "PLANKS", 2),
        r("CRAFTING_TABLE", "CRAFTING_TABLE", 1, false, "PLANKS", 4),
        r("WOODEN_PICKAXE", "WOODEN_PICKAXE", 1, true, "PLANKS", 3, "STICK", 2),
        r("WOODEN_AXE", "WOODEN_AXE", 1, true, "PLANKS", 3, "STICK", 2),
        r("WOODEN_SWORD", "WOODEN_SWORD", 1, true, "PLANKS", 2, "STICK", 1),
        r("STONE_PICKAXE", "STONE_PICKAXE", 1, true, "COBBLESTONE", 3, "STICK", 2),
        r("STONE_AXE", "STONE_AXE", 1, true, "COBBLESTONE", 3, "STICK", 2),
        r("STONE_SWORD", "STONE_SWORD", 1, true, "COBBLESTONE", 2, "STICK", 1),
        r("FURNACE", "FURNACE", 1, true, "COBBLESTONE", 8));

    public static Optional<Recipe> forResult(String result) {
        return ALL.stream().filter(x -> x.result().equals(result)).findFirst();
    }

    public static boolean matches(String group, String item) {
        return switch (group) {
            case "LOG" -> item.endsWith("_LOG") && !item.startsWith("STRIPPED_");
            case "PLANKS" -> item.endsWith("_PLANKS");
            default -> group.equals(item);
        };
    }

    public static int count(Map<String, Integer> have, String group) {
        int n = 0;
        for (Map.Entry<String, Integer> e : have.entrySet()) if (matches(group, e.getKey())) n += e.getValue();
        return n;
    }

    public static boolean canCraft(Map<String, Integer> have, Recipe r, int times) {
        for (Ingredient i : r.ingredients()) if (count(have, i.item()) < i.count() * times) return false;
        return times > 0;
    }

    /**
     * Applies a craft to a counts map: consumes ingredients (alphabetical concrete names, deterministic) and adds the result.
     * PLANKS inherit the wood type of the consumed log. Returns false (and changes nothing) if ingredients are missing.
     */
    public static boolean apply(Map<String, Integer> have, Recipe r, int times) {
        if (!canCraft(have, r, times)) return false;
        String derivedPlanks = null;
        for (Ingredient i : r.ingredients()) {
            int left = i.count() * times;
            for (String name : new TreeMap<>(have).keySet()) {
                if (left == 0) break;
                if (!matches(i.item(), name)) continue;
                int take = Math.min(left, have.get(name));
                if (r.id().equals("PLANKS") && derivedPlanks == null) derivedPlanks = name.replace("_LOG", "_PLANKS");
                have.merge(name, -take, Integer::sum);
                if (have.get(name) == 0) have.remove(name);
                left -= take;
            }
        }
        String out = r.id().equals("PLANKS") ? derivedPlanks : r.result();
        have.merge(out, r.resultCount() * times, Integer::sum);
        return true;
    }
}
