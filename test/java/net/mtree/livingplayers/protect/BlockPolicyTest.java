package net.mtree.livingplayers.protect;

import net.mtree.livingplayers.protect.BlockPolicy.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BlockPolicyTest {
    static final Context CLEAN = new Context(true, false, null, false, true, false);
    static Context with(boolean enabled, boolean region, String claim, boolean artificial, boolean leaves, boolean botPlaced) {
        return new Context(enabled, region, claim, artificial, leaves, botPlaced);
    }

    @Test void allowsNaturalResourcesInCleanSurroundings() {
        for (String m : new String[] {"STONE", "ANDESITE", "COAL_ORE", "DIRT", "GRASS_BLOCK", "SAND", "GRAVEL", "OAK_LOG", "BIRCH_LOG"})
            assertTrue(BlockPolicy.decide(Op.BREAK, m, CLEAN).allowed(), m);
    }
    @Test void deniesEverythingWhenDisabledProtectedOrClaimed() {
        assertFalse(BlockPolicy.decide(Op.BREAK, "STONE", with(false, false, null, false, true, false)).allowed());
        assertFalse(BlockPolicy.decide(Op.BREAK, "STONE", with(true, true, null, false, true, false)).allowed());
        Verdict v = BlockPolicy.decide(Op.BREAK, "STONE", with(true, false, "WorldGuard", false, true, false));
        assertFalse(v.allowed());
        assertTrue(v.reason().contains("WorldGuard"));
        assertFalse(BlockPolicy.decide(Op.PLACE, "CRAFTING_TABLE", with(true, false, "GriefPrevention", false, true, false)).allowed());
        assertFalse(BlockPolicy.decide(Op.PLACE, "CRAFTING_TABLE", with(true, true, null, false, true, false)).allowed());
    }
    @Test void neverBreaksAnythingThatIsNotAWhitelistedNaturalBlock() {
        for (String m : new String[] {"OAK_PLANKS", "COBBLESTONE", "STONE_BRICKS", "CHEST", "CRAFTING_TABLE", "FURNACE", "GLASS", "OAK_DOOR", "BEDROCK",
            "OBSIDIAN", "DIAMOND_ORE", "SPAWNER", "TNT", "STRIPPED_OAK_LOG", "OAK_LEAVES", "WHITE_WOOL", "DIAMOND_BLOCK", "OAK_SIGN", "AIR", "WATER", "LAVA"})
            assertFalse(BlockPolicy.decide(Op.BREAK, m, CLEAN).allowed(), m + " must be refused");
    }
    @Test void logsOnlyFromNaturalTrees() {
        assertFalse(BlockPolicy.decide(Op.BREAK, "OAK_LOG", with(true, false, null, false, false, false)).allowed(), "log cabin without natural leaves");
        assertTrue(BlockPolicy.decide(Op.BREAK, "OAK_LOG", with(true, false, null, false, true, false)).allowed());
    }
    @Test void nothingNearPlayerMadeStructures() {
        assertFalse(BlockPolicy.decide(Op.BREAK, "STONE", with(true, false, null, true, true, false)).allowed());
        assertFalse(BlockPolicy.decide(Op.BREAK, "OAK_LOG", with(true, false, null, true, true, false)).allowed());
    }
    @Test void placementIsLimitedToCraftingTables() {
        assertTrue(BlockPolicy.decide(Op.PLACE, "CRAFTING_TABLE", CLEAN).allowed());
        for (String m : new String[] {"TNT", "LAVA", "WATER", "STONE", "OAK_PLANKS", "CHEST", "FURNACE", "OBSIDIAN", "FIRE", "COBWEB"})
            assertFalse(BlockPolicy.decide(Op.PLACE, m, CLEAN).allowed(), m);
    }
    @Test void botMayPickUpItsOwnTableEvenNextToItsOwnWork() {
        assertTrue(BlockPolicy.decide(Op.BREAK, "CRAFTING_TABLE", with(true, false, null, true, false, true)).allowed());
        assertFalse(BlockPolicy.decide(Op.BREAK, "CRAFTING_TABLE", with(true, false, null, true, false, false)).allowed(), "a player's table is never touched");
        assertFalse(BlockPolicy.decide(Op.BREAK, "CHEST", with(true, false, null, false, false, true)).allowed(), "bot-placed registry never widens what may be broken");
    }
    @Test void exhaustiveSweepOnlyWhitelistAndLogsCanEverBeBroken() {
        String[] sample = {"STONE", "GRANITE", "DIORITE", "ANDESITE", "COAL_ORE", "DEEPSLATE_COAL_ORE", "DIRT", "GRASS_BLOCK", "SAND", "GRAVEL"};
        int allowed = 0;
        for (String m : sample) if (BlockPolicy.decide(Op.BREAK, m, CLEAN).allowed()) allowed++;
        assertEquals(sample.length, allowed);
    }
}
