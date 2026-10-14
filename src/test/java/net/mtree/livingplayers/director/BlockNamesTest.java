package net.mtree.livingplayers.director;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockNamesTest {
    @Test void naturalBlocksAreNotArtificial() {
        for (String n : new String[] {"GRASS_BLOCK", "DIRT", "STONE", "OAK_LOG", "OAK_LEAVES", "SAND", "TERRACOTTA", "SNOW", "SHORT_GRASS", "COBBLESTONE"})
            assertFalse(BlockNames.isArtificial(n), n);
    }
    @Test void builtBlocksAreArtificial() {
        for (String n : new String[] {"OAK_PLANKS", "STONE_BRICKS", "GLASS_PANE", "TORCH", "OAK_DOOR", "RED_BED", "CHEST", "FURNACE",
            "CRAFTING_TABLE", "OAK_FENCE", "COBBLESTONE_STAIRS", "OAK_SLAB", "WHITE_WOOL", "WHITE_CONCRETE"})
            assertTrue(BlockNames.isArtificial(n), n);
    }
    @Test void hazardsAndWaterPlants() {
        assertTrue(BlockNames.isHazard("CACTUS"));
        assertTrue(BlockNames.isHazard("MAGMA_BLOCK"));
        assertFalse(BlockNames.isHazard("GRASS_BLOCK"));
        assertTrue(BlockNames.isWaterPlant("KELP"));
        assertFalse(BlockNames.isWaterPlant("GRASS_BLOCK"));
    }
    @Test void onlyWildPlantsAreReplaceable() {
        assertTrue(BlockNames.isReplaceablePlant("SHORT_GRASS"));
        assertTrue(BlockNames.isReplaceablePlant("POPPY"));
        for (String n : new String[] {"WHEAT", "CARROTS", "OAK_SAPLING", "TORCH", "SUGAR_CANE", "SWEET_BERRY_BUSH", "OAK_SIGN", "RAIL", "STONE"})
            assertFalse(BlockNames.isReplaceablePlant(n), n);
    }
}
