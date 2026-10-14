package net.mtree.livingplayers.director;

import java.util.List;
import java.util.Set;

/**
 * Name-based heuristics so spawn-safety classification stays testable without Bukkit.
 * "Artificial" is a HEURISTIC for "probably built by a player or generated structure"; it is
 * deliberately conservative (it makes spawns rarer, never more dangerous).
 */
public final class BlockNames {
    private BlockNames() {}

    private static final List<String> ARTIFICIAL_PARTS = List.of(
        "PLANKS", "BRICK", "GLASS", "TORCH", "_DOOR", "_BED", "CHEST", "FURNACE", "CRAFTING_TABLE", "FENCE",
        "STAIRS", "_SLAB", "LANTERN", "CARPET", "BARREL", "_SIGN", "LADDER", "RAIL", "CONCRETE", "WOOL",
        "BOOKSHELF", "ANVIL", "BANNER", "FLOWER_POT", "HAY_BLOCK", "SMOKER", "BLAST_FURNACE", "LECTERN",
        "COMPOSTER", "LOOM", "GRINDSTONE", "STONECUTTER", "CAULDRON", "CAMPFIRE", "TRAPDOOR", "BEACON",
        "ENCHANTING_TABLE", "JUKEBOX", "NOTE_BLOCK", "OBSERVER", "PISTON", "HOPPER", "DISPENSER", "DROPPER");

    private static final Set<String> HAZARDS = Set.of(
        "FIRE", "SOUL_FIRE", "CAMPFIRE", "SOUL_CAMPFIRE", "CACTUS", "MAGMA_BLOCK", "SWEET_BERRY_BUSH",
        "WITHER_ROSE", "POWDER_SNOW", "POINTED_DRIPSTONE", "COBWEB", "TNT", "LAVA_CAULDRON", "BIG_DRIPLEAF");

    private static final Set<String> WATER_PLANTS = Set.of(
        "KELP", "KELP_PLANT", "SEAGRASS", "TALL_SEAGRASS", "BUBBLE_COLUMN");

    /** Wild plants a placed block may replace (vanilla placement replaces these). Crops, saplings, torches etc. are NOT listed. */
    private static final Set<String> REPLACEABLE_PLANTS = Set.of("SHORT_GRASS", "TALL_GRASS", "FERN", "LARGE_FERN", "DEAD_BUSH", "DANDELION",
        "POPPY", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY", "ALLIUM", "BLUE_ORCHID", "RED_TULIP", "ORANGE_TULIP",
        "WHITE_TULIP", "PINK_TULIP", "SNOW");

    public static boolean isReplaceablePlant(String materialName) { return REPLACEABLE_PLANTS.contains(materialName); }

    public static boolean isArtificial(String materialName) {
        for (String part : ARTIFICIAL_PARTS) if (materialName.contains(part)) return true;
        return false;
    }

    public static boolean isHazard(String materialName) { return HAZARDS.contains(materialName); }

    public static boolean isWaterPlant(String materialName) { return WATER_PLANTS.contains(materialName); }
}
