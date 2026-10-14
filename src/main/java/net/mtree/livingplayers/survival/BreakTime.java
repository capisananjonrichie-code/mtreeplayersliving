package net.mtree.livingplayers.survival;

import java.util.List;
import java.util.Map;

/**
 * Vanilla mining rules by material name: hardness, effective tool, harvest tier, and break time in ticks.
 * (Actual DROPS come from the server via Block#getDrops(tool); this only decides speed and tool choice.) Pure.
 */
public final class BreakTime {
    private BreakTime() {}

    public enum Kind { PICKAXE, AXE, SHOVEL, NONE }

    private static final Map<String, Double> TIER_SPEED = Map.of("WOODEN", 2.0, "STONE", 4.0, "IRON", 6.0, "DIAMOND", 8.0, "NETHERITE", 9.0, "GOLDEN", 12.0);
    private static final Map<String, Integer> TIER_RANK = Map.of("WOODEN", 0, "GOLDEN", 0, "STONE", 1, "IRON", 2, "DIAMOND", 3, "NETHERITE", 4);

    public static double hardness(String block) {
        if (block.endsWith("_LOG")) return 2.0;
        if (block.endsWith("_PLANKS")) return 2.0;
        if (block.endsWith("_LEAVES")) return 0.2;
        return switch (block) {
            case "STONE", "GRANITE", "DIORITE", "ANDESITE" -> 1.5;
            case "COBBLESTONE" -> 2.0;
            case "COAL_ORE", "IRON_ORE", "COPPER_ORE", "GOLD_ORE", "DIAMOND_ORE" -> 3.0;
            case "DEEPSLATE", "COBBLED_DEEPSLATE" -> 3.0;
            case "DEEPSLATE_COAL_ORE", "DEEPSLATE_IRON_ORE", "DEEPSLATE_DIAMOND_ORE" -> 4.5;
            case "DIRT", "SAND" -> 0.5;
            case "GRASS_BLOCK", "GRAVEL" -> 0.6;
            case "CRAFTING_TABLE" -> 2.5;
            default -> 1.0;
        };
    }

    /** The tool kind that mines this block fastest. */
    public static Kind effectiveKind(String block) {
        if (block.endsWith("_LOG") || block.endsWith("_PLANKS") || block.equals("CRAFTING_TABLE")) return Kind.AXE;
        if (block.equals("DIRT") || block.equals("SAND") || block.equals("GRAVEL") || block.equals("GRASS_BLOCK")) return Kind.SHOVEL;
        if (block.startsWith("STONE") || block.endsWith("_ORE") || block.equals("COBBLESTONE") || block.contains("DEEPSLATE")
            || block.equals("GRANITE") || block.equals("DIORITE") || block.equals("ANDESITE")) return Kind.PICKAXE;
        return Kind.NONE;
    }

    /** Minimum pickaxe tier rank needed for the block to drop anything (-1 = no pickaxe needed, 0 = any pickaxe). */
    public static int requiredPickaxeRank(String block) {
        return switch (block) {
            case "IRON_ORE", "DEEPSLATE_IRON_ORE", "COPPER_ORE", "LAPIS_ORE" -> 1;
            case "GOLD_ORE", "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "REDSTONE_ORE" -> 2;
            default -> effectiveKind(block) == Kind.PICKAXE ? 0 : -1;
        };
    }

    public record Tool(Kind kind, String tier) {
        public static Tool of(String item) {
            if (item == null) return new Tool(Kind.NONE, "");
            int u = item.indexOf('_');
            if (u < 0) return new Tool(Kind.NONE, "");
            String tier = item.substring(0, u), rest = item.substring(u + 1);
            if (!TIER_SPEED.containsKey(tier)) return new Tool(Kind.NONE, "");
            return switch (rest) {
                case "PICKAXE" -> new Tool(Kind.PICKAXE, tier);
                case "AXE" -> new Tool(Kind.AXE, tier);
                case "SHOVEL" -> new Tool(Kind.SHOVEL, tier);
                default -> new Tool(Kind.NONE, "");
            };
        }
        double speedFor(String block) { return kind != Kind.NONE && kind == effectiveKind(block) ? TIER_SPEED.get(tier) : 1.0; }
    }

    public static boolean canHarvest(String block, String toolItem) {
        int need = requiredPickaxeRank(block);
        if (need < 0) return true;
        Tool t = Tool.of(toolItem);
        return t.kind() == Kind.PICKAXE && TIER_RANK.get(t.tier()) >= need;
    }

    /** Vanilla: damage per tick = speed / hardness / (30 if harvestable else 100); ticks = ceil(1 / damage). */
    public static int ticks(String block, String toolItem) {
        double speed = Tool.of(toolItem).speedFor(block);
        double perTick = speed / hardness(block) / (canHarvest(block, toolItem) ? 30.0 : 100.0);
        return Math.max(1, (int) Math.ceil(1.0 / perTick));
    }

    /** Index of the best tool for the block among the given item names (null entries allowed); -1 = use the hand. */
    public static int bestToolIndex(List<String> names, String block) {
        int best = -1;
        int bestTicks = ticks(block, null);
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i) == null || Tool.of(names.get(i)).kind() == Kind.NONE) continue;
            int t = ticks(block, names.get(i));
            if (t < bestTicks) { bestTicks = t; best = i; }
        }
        return best;
    }
}
