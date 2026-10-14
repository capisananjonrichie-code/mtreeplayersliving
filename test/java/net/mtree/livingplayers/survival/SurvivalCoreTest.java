package net.mtree.livingplayers.survival;

import net.mtree.livingplayers.survival.Recipes.Recipe;
import net.mtree.livingplayers.survival.SurvivalPlanner.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SurvivalCoreTest {
    // ---------------------------------------------------------------- break time
    @Test void breakTimesMatchVanilla() {
        assertEquals(60, BreakTime.ticks("OAK_LOG", null), "log by hand = 3.0 s");
        assertEquals(150, BreakTime.ticks("STONE", null), "stone by hand = 7.5 s (and drops nothing)");
        assertEquals(23, BreakTime.ticks("STONE", "WOODEN_PICKAXE"), "wooden pickaxe on stone ~ 0.75 s");
        assertTrue(BreakTime.ticks("STONE", "STONE_PICKAXE") < BreakTime.ticks("STONE", "WOODEN_PICKAXE"));
        assertTrue(BreakTime.ticks("OAK_LOG", "STONE_AXE") < BreakTime.ticks("OAK_LOG", null));
        assertEquals(BreakTime.ticks("OAK_LOG", null), BreakTime.ticks("OAK_LOG", "STONE_PICKAXE"), "wrong tool kind gives no speed-up");
    }
    @Test void harvestRules() {
        assertFalse(BreakTime.canHarvest("STONE", null));
        assertTrue(BreakTime.canHarvest("STONE", "WOODEN_PICKAXE"));
        assertTrue(BreakTime.canHarvest("COAL_ORE", "WOODEN_PICKAXE"));
        assertFalse(BreakTime.canHarvest("IRON_ORE", "WOODEN_PICKAXE"));
        assertTrue(BreakTime.canHarvest("IRON_ORE", "STONE_PICKAXE"));
        assertFalse(BreakTime.canHarvest("DIAMOND_ORE", "STONE_PICKAXE"));
        assertTrue(BreakTime.canHarvest("DIAMOND_ORE", "IRON_PICKAXE"));
        assertTrue(BreakTime.canHarvest("OAK_LOG", null));
        assertTrue(BreakTime.canHarvest("DIRT", null));
    }
    @Test void bestToolChoice() {
        List<String> inv = Arrays.asList("DIRT", "WOODEN_PICKAXE", null, "IRON_PICKAXE", "STONE_AXE");
        assertEquals(3, BreakTime.bestToolIndex(inv, "STONE"));
        assertEquals(4, BreakTime.bestToolIndex(inv, "OAK_LOG"));
        assertEquals(-1, BreakTime.bestToolIndex(Arrays.asList("DIRT", null), "STONE"));
        assertEquals(-1, BreakTime.bestToolIndex(Arrays.asList("WOODEN_PICKAXE"), "OAK_LOG"), "a pickaxe is no better than a hand on a log");
    }

    // ---------------------------------------------------------------- recipes
    static Map<String, Integer> inv(Object... kv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }
    static Recipe rec(String id) { return Recipes.forResult(id).orElseThrow(); }

    @Test void craftingConsumesIngredientsAndAddsExactResult() {
        Map<String, Integer> m = inv("OAK_LOG", 2);
        assertTrue(Recipes.apply(m, rec("PLANKS"), 2));
        assertEquals(Map.of("OAK_PLANKS", 8), m, "2 logs -> 8 planks of the SAME wood, no logs left");
        assertTrue(Recipes.apply(m, rec("STICK"), 1));
        assertEquals(Map.of("OAK_PLANKS", 6, "STICK", 4), m);
    }
    @Test void cannotCraftWithoutIngredientsAndNothingChanges() {
        Map<String, Integer> m = inv("OAK_PLANKS", 2, "STICK", 2);
        Map<String, Integer> before = new HashMap<>(m);
        assertFalse(Recipes.apply(m, rec("WOODEN_PICKAXE"), 1));
        assertEquals(before, m);
        assertFalse(Recipes.canCraft(m, rec("PLANKS"), 1));
        assertFalse(Recipes.canCraft(m, rec("STICK"), 0));
    }
    @Test void groupsMatchAnyWoodButNotStripped() {
        assertTrue(Recipes.matches("LOG", "BIRCH_LOG"));
        assertFalse(Recipes.matches("LOG", "STRIPPED_OAK_LOG"));
        assertFalse(Recipes.matches("LOG", "OAK_PLANKS"));
        assertTrue(Recipes.matches("PLANKS", "SPRUCE_PLANKS"));
        Map<String, Integer> mixed = inv("OAK_PLANKS", 2, "BIRCH_PLANKS", 2);
        assertTrue(Recipes.apply(mixed, rec("CRAFTING_TABLE"), 1), "mixed wood planks are fine for a table");
        assertEquals(Map.of("CRAFTING_TABLE", 1), mixed);
    }
    @Test void everyRecipeConservesItemsRandomisedFuzz() {
        Random r = new Random(5);
        for (int i = 0; i < 2000; i++) {
            Map<String, Integer> m = new HashMap<>();
            for (String it : List.of("OAK_LOG", "OAK_PLANKS", "STICK", "COBBLESTONE", "BIRCH_LOG")) if (r.nextBoolean()) m.put(it, 1 + r.nextInt(20));
            Recipe rc = Recipes.ALL.get(r.nextInt(Recipes.ALL.size()));
            int times = 1 + r.nextInt(3);
            Map<String, Integer> before = new HashMap<>(m);
            boolean could = Recipes.canCraft(before, rc, times);
            boolean did = Recipes.apply(m, rc, times);
            assertEquals(could, did);
            assertTrue(m.values().stream().allMatch(v -> v > 0), "no zero/negative stacks left behind");
            if (did) {
                int in = rc.ingredients().stream().mapToInt(x -> x.count() * times).sum();
                int beforeTotal = before.values().stream().mapToInt(Integer::intValue).sum();
                int afterTotal = m.values().stream().mapToInt(Integer::intValue).sum();
                assertEquals(beforeTotal - in + rc.resultCount() * times, afterTotal, "items in = items consumed + produced");
            } else assertEquals(before, m);
        }
    }

    // ---------------------------------------------------------------- planner
    /** Simulates a bot: gathering adds ONLY the raw resource that was asked for (as a real block would). */
    static Map<String, Integer> simulate(Map<String, Integer> start, boolean tablePlacedAlready, List<Step> trace) {
        Map<String, Integer> m = new HashMap<>(start);
        boolean table = tablePlacedAlready;
        for (int i = 0; i < 200; i++) {
            Step s = SurvivalPlanner.nextStep(m, new Env(table));
            trace.add(s);
            switch (s.type()) {
                case DONE -> { return m; }
                case BLOCKED -> fail("planner blocked: " + s);
                case GATHER -> m.merge(s.what().equals("LOG") ? "OAK_LOG" : s.what(), s.amount(), Integer::sum);
                case CRAFT -> assertTrue(Recipes.apply(m, rec(s.what()), s.amount()), "planner asked for an impossible craft " + s);
                case PLACE_TABLE -> { assertTrue(m.getOrDefault("CRAFTING_TABLE", 0) >= 1); m.merge("CRAFTING_TABLE", -1, Integer::sum); m.remove("CRAFTING_TABLE", 0); table = true; }
            }
        }
        fail("planner did not terminate");
        return m;
    }

    @Test void plannerReachesStoneToolsFromNothingUsingOnlyLegitimateSteps() {
        List<Step> trace = new ArrayList<>();
        Map<String, Integer> end = simulate(new HashMap<>(), false, trace);
        for (String tool : SurvivalPlanner.LADDER) assertEquals(1, end.getOrDefault(tool, 0), tool);
        assertEquals(Type.GATHER, trace.get(0).type());
        assertEquals("LOG", trace.get(0).what(), "the very first need is wood");
        int gatherSteps = (int) trace.stream().filter(s -> s.type() == Type.GATHER).count();
        assertTrue(gatherSteps >= 2 && gatherSteps <= 12, "reasonable number of gather steps: " + gatherSteps);
        assertTrue(trace.stream().anyMatch(s -> s.type() == Type.PLACE_TABLE), "a crafting table must be placed for tool recipes");
        // stone is never requested before a pickaxe exists
        boolean havePick = false;
        Map<String, Integer> m = new HashMap<>();
        for (Step s : trace) {
            if (s.type() == Type.CRAFT && s.what().equals("WOODEN_PICKAXE")) havePick = true;
            if (s.type() == Type.GATHER && s.what().equals("COBBLESTONE")) assertTrue(havePick, "cobblestone gathered before a pickaxe");
        }
    }
    @Test void plannerNeverAsksForWhatItAlreadyHas() {
        Map<String, Integer> rich = inv("WOODEN_PICKAXE", 1, "WOODEN_AXE", 1, "STONE_PICKAXE", 1, "STONE_AXE", 1, "STONE_SWORD", 1);
        assertEquals(Type.DONE, SurvivalPlanner.nextStep(rich, new Env(true)).type());
        Map<String, Integer> planks = inv("OAK_PLANKS", 20, "STICK", 10);
        Step s = SurvivalPlanner.nextStep(planks, new Env(true));
        assertEquals(Type.CRAFT, s.type());
        assertEquals("WOODEN_PICKAXE", s.what());
    }
    @Test void plannerPlacesAnExistingTableInsteadOfCraftingAnother() {
        Map<String, Integer> m = inv("OAK_PLANKS", 6, "STICK", 4, "CRAFTING_TABLE", 1);
        assertEquals(Type.PLACE_TABLE, SurvivalPlanner.nextStep(m, new Env(false)).type());
    }
    @Test void plannerGathersMinimumLogsForFirstSteps() {
        Step s = SurvivalPlanner.nextStep(new HashMap<>(), new Env(false));
        assertEquals(Type.GATHER, s.type());
        assertTrue(s.amount() >= 1);
    }

    @Test void plannerCanSupplyAWholeHouseWorthOfPlanksFromNothing() {
        Map<String, Integer> m = new HashMap<>();
        Map<String, Integer> wanted = Map.of("PLANKS", 69);
        int logs = 0;
        for (int i = 0; i < 200; i++) {
            Step s = SurvivalPlanner.nextStepForItems(m, new Env(false), wanted);
            if (s.type() == Type.DONE) break;
            switch (s.type()) {
                case GATHER -> { assertEquals("LOG", s.what()); m.merge("OAK_LOG", s.amount(), Integer::sum); logs += s.amount(); }
                case CRAFT -> assertTrue(Recipes.apply(m, rec(s.what()), s.amount()));
                default -> fail("unexpected " + s);
            }
        }
        assertTrue(Recipes.count(m, "PLANKS") >= 69);
        assertEquals(18, logs, "69 planks need exactly 18 logs (4 planks per log): nothing is conjured, nothing is wasted");
        assertEquals(0, Recipes.count(m, "LOG"), "all logs were converted");
    }

    // ---------------------------------------------------------------- inventory planner
    @Test void surplusNeverTouchesValuables() {
        Map<String, Integer> m = inv("DIAMOND", 500, "NETHERITE_INGOT", 9, "DIAMOND_PICKAXE", 1, "COBBLESTONE", 300, "GRAVEL", 40, "DIRT", 20, "OAK_LOG", 500, "IRON_INGOT", 99);
        Map<String, Integer> s = InventoryPlanner.surplus(m);
        assertEquals(172, s.get("COBBLESTONE"));
        assertEquals(40, s.get("GRAVEL"));
        assertFalse(s.containsKey("DIRT"), "under the keep limit");
        for (String valuable : List.of("DIAMOND", "NETHERITE_INGOT", "DIAMOND_PICKAXE", "OAK_LOG", "IRON_INGOT")) assertFalse(s.containsKey(valuable), valuable);
    }
}
