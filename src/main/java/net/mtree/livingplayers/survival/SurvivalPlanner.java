package net.mtree.livingplayers.survival;

import net.mtree.livingplayers.survival.Recipes.Ingredient;
import net.mtree.livingplayers.survival.Recipes.Recipe;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Decides the next step toward a tool ladder (wooden then stone tools) from what the bot owns. It only ever asks for
 * GATHER of raw resources or CRAFT with ingredients it already has, so every item has a legitimate source. Pure.
 */
public final class SurvivalPlanner {
    private SurvivalPlanner() {}

    public enum Type { GATHER, CRAFT, PLACE_TABLE, DONE, BLOCKED }

    /** @param what GATHER: resource group (LOG/COBBLESTONE); CRAFT: recipe id; BLOCKED: reason */
    public record Step(Type type, String what, int amount) {
        static Step gather(String g, int n) { return new Step(Type.GATHER, g, n); }
        static Step craft(String id, int times) { return new Step(Type.CRAFT, id, times); }
    }

    public record Env(boolean tableNearby) {}

    /** Ladder, in order. The sword comes last because it is the cheapest combat upgrade. */
    public static final List<String> LADDER = List.of("WOODEN_PICKAXE", "WOODEN_AXE", "STONE_PICKAXE", "STONE_AXE", "STONE_SWORD");

    public static Step nextStep(Map<String, Integer> have, Env env) {
        for (String target : LADDER) {
            if (Recipes.count(have, target) >= 1) continue;
            Optional<Step> s = resolve(target, 1, have, env, 0);
            if (s.isPresent()) return s.get();
        }
        return new Step(Type.DONE, "ladder complete", 0);
    }

    /** Steps toward holding at least the wanted counts (e.g. {"PLANKS": 69}) - raw gathering and crafting only. */
    public static Step nextStepForItems(Map<String, Integer> have, Env env, Map<String, Integer> wanted) {
        for (Map.Entry<String, Integer> w : wanted.entrySet()) {
            Optional<Step> s = resolve(w.getKey(), w.getValue(), have, env, 0);
            if (s.isPresent()) return s.get();
        }
        return new Step(Type.DONE, "wanted items satisfied", 0);
    }

    private static Optional<Step> resolve(String item, int qty, Map<String, Integer> have, Env env, int depth) {
        if (depth > 8) return Optional.of(new Step(Type.BLOCKED, "recipe depth exceeded", 0));
        int owned = Recipes.count(have, item);
        if (owned >= qty) return Optional.empty();
        int missing = qty - owned;

        Optional<Recipe> rec = Recipes.forResult(item);
        if (rec.isEmpty()) { // raw resource
            return switch (item) {
                case "LOG" -> Optional.of(Step.gather("LOG", missing));
                case "COBBLESTONE" -> {
                    // stone only drops with a pickaxe: the wooden pickaxe must exist first
                    if (Recipes.count(have, "WOODEN_PICKAXE") + Recipes.count(have, "STONE_PICKAXE") == 0) yield resolve("WOODEN_PICKAXE", 1, have, env, depth + 1);
                    yield Optional.of(Step.gather("COBBLESTONE", missing));
                }
                default -> Optional.of(new Step(Type.BLOCKED, "no source for " + item, 0));
            };
        }
        Recipe r = rec.get();
        int times = (int) Math.ceil(missing / (double) r.resultCount());
        for (Ingredient in : r.ingredients()) {
            Optional<Step> s = resolve(in.item(), in.count() * times, have, env, depth + 1);
            if (s.isPresent()) return s;
        }
        if (r.needsTable() && !env.tableNearby()) {
            if (Recipes.count(have, "CRAFTING_TABLE") >= 1) return Optional.of(new Step(Type.PLACE_TABLE, "CRAFTING_TABLE", 1));
            return resolve("CRAFTING_TABLE", 1, have, env, depth + 1);
        }
        return Optional.of(Step.craft(r.id(), times));
    }
}
