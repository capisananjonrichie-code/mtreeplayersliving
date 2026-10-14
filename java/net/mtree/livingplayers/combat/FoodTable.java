package net.mtree.livingplayers.combat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Safe foods only (no poison/hunger effects, no bowl-returning stews). Values are vanilla hunger points / saturation. */
public final class FoodTable {
    private FoodTable() {}

    public record Food(int nutrition, float saturation) {}

    private static final Map<String, Food> FOODS = Map.ofEntries(
        Map.entry("COOKED_BEEF", new Food(8, 12.8f)), Map.entry("COOKED_PORKCHOP", new Food(8, 12.8f)),
        Map.entry("COOKED_MUTTON", new Food(6, 9.6f)), Map.entry("COOKED_CHICKEN", new Food(6, 7.2f)),
        Map.entry("COOKED_RABBIT", new Food(5, 6f)), Map.entry("COOKED_COD", new Food(5, 6f)),
        Map.entry("COOKED_SALMON", new Food(6, 9.6f)), Map.entry("BREAD", new Food(5, 6f)),
        Map.entry("BAKED_POTATO", new Food(5, 6f)), Map.entry("APPLE", new Food(4, 2.4f)),
        Map.entry("CARROT", new Food(3, 3.6f)), Map.entry("GOLDEN_CARROT", new Food(6, 14.4f)),
        Map.entry("BEEF", new Food(3, 1.8f)), Map.entry("PORKCHOP", new Food(3, 1.8f)),
        Map.entry("MUTTON", new Food(2, 1.2f)), Map.entry("COD", new Food(2, 0.4f)),
        Map.entry("SALMON", new Food(2, 0.4f)), Map.entry("MELON_SLICE", new Food(2, 1.2f)),
        Map.entry("SWEET_BERRIES", new Food(2, 0.4f)), Map.entry("COOKIE", new Food(2, 0.4f)),
        Map.entry("PUMPKIN_PIE", new Food(8, 4.8f)), Map.entry("BEETROOT", new Food(1, 1.2f)),
        Map.entry("POTATO", new Food(1, 0.6f)), Map.entry("DRIED_KELP", new Food(1, 0.6f)));

    public static Optional<Food> of(String material) { return Optional.ofNullable(FOODS.get(material)); }

    /** Smallest food that covers the missing hunger (least waste), otherwise the most nutritious. -1 if none edible. */
    public static int bestFoodIndex(List<String> names, int hunger) {
        int missing = Math.max(1, 20 - hunger);
        int bestCover = -1, bestBig = -1;
        for (int i = 0; i < names.size(); i++) {
            Food f = names.get(i) == null ? null : FOODS.get(names.get(i));
            if (f == null) continue;
            if (f.nutrition() >= missing && (bestCover < 0 || f.nutrition() < FOODS.get(names.get(bestCover)).nutrition())) bestCover = i;
            if (bestBig < 0 || f.nutrition() > FOODS.get(names.get(bestBig)).nutrition()) bestBig = i;
        }
        return bestCover >= 0 ? bestCover : bestBig;
    }
}
