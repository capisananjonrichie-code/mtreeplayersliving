package net.mtree.livingplayers.building;

import net.mtree.livingplayers.building.Blueprint.Placement;
import net.mtree.livingplayers.director.BlockClass;
import net.mtree.livingplayers.director.TerrainProbe;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static net.mtree.livingplayers.director.BlockClass.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildingCoreTest {
    static Blueprint hut() throws Exception {
        String json = Files.readString(Path.of("src/main/resources/starter_hut.json"));
        BlueprintParser.Result r = BlueprintParser.parse(json);
        assertTrue(r.ok(), String.valueOf(r.error()));
        return r.blueprint();
    }

    // ---------------------------------------------------------------- blueprint
    @Test void starterHutParsesWithExpectedShapeAndMaterialBill() throws Exception {
        Blueprint b = hut();
        assertEquals("starter_hut", b.name());
        assertEquals(5, b.width()); assertEquals(5, b.depth()); assertEquals(4, b.height());
        assertEquals(69, b.placements().size());
        assertEquals(Map.of("PLANKS", 69), b.materialCounts());
        // doorway (x=2,z=0) is open on layers 0 and 1, closed by a lintel on layer 2
        Set<String> cells = new HashSet<>();
        for (Placement p : b.placements()) cells.add(p.x() + "," + p.y() + "," + p.z());
        assertFalse(cells.contains("2,0,0")); assertFalse(cells.contains("2,1,0")); assertTrue(cells.contains("2,2,0"));
        assertFalse(cells.contains("0,1,2")); assertFalse(cells.contains("4,1,2"), "window gaps");
    }
    @Test void rotationPreservesCountsAndStaysInsideTheFootprint() throws Exception {
        Blueprint b = hut();
        for (int q = 0; q < 8; q++) {
            Blueprint r = b.rotated(q);
            assertEquals(69, r.placements().size());
            assertEquals(new HashSet<>(b.placements().stream().map(p -> p.x() + "," + p.y() + "," + p.z()).toList()).size(), r.placements().size(), "no collisions");
            for (Placement p : r.placements()) {
                assertTrue(p.x() >= 0 && p.x() < r.width() && p.z() >= 0 && p.z() < r.depth(), "q=" + q + " " + p);
            }
        }
        assertEquals(b.placements().stream().map(p -> p.x() + "," + p.y() + "," + p.z()).collect(java.util.stream.Collectors.toSet()),
            b.rotated(4).placements().stream().map(p -> p.x() + "," + p.y() + "," + p.z()).collect(java.util.stream.Collectors.toSet()), "4 turns = identity");
    }
    @Test void rotationMovesTheDoorway() throws Exception {
        Blueprint r = hut().rotated(1);
        Set<String> cells = new HashSet<>();
        for (Placement p : r.placements()) cells.add(p.x() + "," + p.y() + "," + p.z());
        assertTrue(cells.contains("2,0,0"), "original doorway cell (2,0,0) is now a wall");
        assertFalse(cells.contains("4,0,2"), "doorway moved to the east side (x=4,z=2)");
    }
    @Test void buildOrderIsBottomUpAndEveryBlockIsSupported() throws Exception {
        Blueprint b = hut();
        List<Placement> order = b.buildOrder();
        Set<String> placed = new HashSet<>();
        int lastY = 0;
        for (Placement p : order) {
            assertTrue(p.y() >= lastY, "layers never go back down");
            lastY = p.y();
            boolean grounded = p.y() == 0;
            boolean below = placed.contains(p.x() + "," + (p.y() - 1) + "," + p.z());
            boolean side = placed.contains((p.x() - 1) + "," + p.y() + "," + p.z()) || placed.contains(p.x() + "," + p.y() + "," + (p.z() - 1))
                || placed.contains((p.x() + 1) + "," + p.y() + "," + p.z()) || placed.contains(p.x() + "," + p.y() + "," + (p.z() + 1));
            assertTrue(grounded || below || side, "unsupported placement " + p);
            placed.add(p.x() + "," + p.y() + "," + p.z());
        }
        assertEquals(69, placed.size());
    }

    // ---------------------------------------------------------------- parser validation
    @Test void parserRejectsBadBlueprintsWithMessages() {
        String ok = "{\"name\":\"t\",\"palette\":{\"W\":\"PLANKS\"},\"layers\":[[\"W\"]]}";
        assertTrue(BlueprintParser.parse(ok).ok());
        assertFalse(BlueprintParser.parse("not json").ok());
        assertFalse(BlueprintParser.parse("{\"name\":\"Bad Name\",\"palette\":{\"W\":\"PLANKS\"},\"layers\":[[\"W\"]]}").ok());
        assertTrue(BlueprintParser.parse("{\"name\":\"t\",\"palette\":{\"W\":\"TNT\"},\"layers\":[[\"W\"]]}").error().contains("not supported"));
        assertTrue(BlueprintParser.parse("{\"name\":\"t\",\"palette\":{\"W\":\"PLANKS\"},\"layers\":[[\"X\"]]}").error().contains("not in the palette"));
        assertFalse(BlueprintParser.parse("{\"name\":\"t\",\"palette\":{\"W\":\"PLANKS\"},\"layers\":[[\"..\"]]}").ok(), "empty blueprint");
        assertFalse(BlueprintParser.parse("{\"name\":\"t\",\"palette\":{\".\":\"PLANKS\"},\"layers\":[[\"W\"]]}").ok());
        StringBuilder big = new StringBuilder("{\"name\":\"t\",\"palette\":{\"W\":\"PLANKS\"},\"layers\":[");
        for (int y = 0; y < 17; y++) big.append(y > 0 ? "," : "").append("[\"W\"]");
        assertFalse(BlueprintParser.parse(big.append("]}").toString()).ok(), "too tall");
    }

    // ---------------------------------------------------------------- progress
    @Test void progressBitsRoundTripAndTolerateGarbage() {
        ProgressBits p = new ProgressBits(69);
        for (int i : new int[] {0, 1, 5, 40, 68}) p.set(i);
        ProgressBits back = ProgressBits.fromHex(69, p.toHex());
        for (int i = 0; i < 69; i++) assertEquals(p.get(i), back.get(i), "bit " + i);
        assertEquals(5, back.count());
        assertEquals(2, back.firstUnset());
        assertEquals(0, ProgressBits.fromHex(69, null).count());
        assertEquals(0, ProgressBits.fromHex(69, "zzzz").count());
        assertTrue(ProgressBits.fromHex(8, "ffffffffffffffff").count() <= 8, "never exceeds size");
    }

    // ---------------------------------------------------------------- site selection
    record P(int x, int y, int z) {}
    static TerrainProbe flat(Map<P, BlockClass> o) { return (x, y, z) -> { BlockClass c = o.get(new P(x, y, z)); return c != null ? c : (y <= 63 ? SOLID : AIR); }; }

    @Test void acceptsOpenFlatGround() {
        assertTrue(SiteSelector.check(flat(Map.of()), 0, 64, 0, 5, 5, 4, 3).ok());
    }
    @Test void rejectsUnloadedWaterObstructionAndPlayerBuildsNearby() {
        assertEquals("chunk not loaded", SiteSelector.check((x, y, z) -> UNKNOWN, 0, 64, 0, 5, 5, 4, 3).reason());
        assertFalse(SiteSelector.check(flat(Map.of(new P(2, 63, 2), WATER)), 0, 64, 0, 5, 5, 4, 3).ok());
        assertFalse(SiteSelector.check(flat(Map.of(new P(2, 66, 2), SOLID)), 0, 64, 0, 5, 5, 4, 3).ok(), "a boulder in the volume");
        assertFalse(SiteSelector.check(flat(Map.of(new P(7, 65, 2), ARTIFICIAL)), 0, 64, 0, 5, 5, 4, 3).ok(), "player wall 2 blocks from the footprint");
        assertTrue(SiteSelector.check(flat(Map.of(new P(12, 65, 2), ARTIFICIAL)), 0, 64, 0, 5, 5, 4, 3).ok(), "far enough away");
        assertFalse(SiteSelector.check(flat(Map.of(new P(-2, 64, -2), LAVA)), 0, 64, 0, 5, 5, 4, 3).ok());
        assertFalse(SiteSelector.check(flat(Map.of(new P(1, 63, 1), ARTIFICIAL)), 0, 64, 0, 5, 5, 4, 3).ok(), "built floor is not natural ground");
    }
    @Test void passableVegetationIsAcceptableAndWillBeReplaced() {
        assertTrue(SiteSelector.check(flat(Map.of(new P(1, 64, 1), PASSABLE, new P(2, 65, 2), PASSABLE)), 0, 64, 0, 5, 5, 4, 3).ok());
    }
    @Test void findPicksNearestFlatSiteAndSkipsSlopes() {
        // terrain is a slope (height = x/3) everywhere except a flat plateau for x>=20
        SiteSelector.Surface surf = (x, z) -> 64 + (x >= 20 ? 6 : Math.floorDiv(x, 3));
        TerrainProbe probe = (x, y, z) -> y <= surf.feetY(x, z) - 1 ? SOLID : AIR;
        SiteSelector.Site s = SiteSelector.find(probe, surf, 0, 0, 40, 5, 5, 4, 2, 500);
        assertNotNull(s);
        assertEquals(70, s.y(), "must land on level ground (plateau starts at x=18), not a slope: " + s);
        for (int x = 0; x < 5; x++) assertEquals(70, surf.feetY(s.x() + x, s.z()), "whole footprint is level");
        assertTrue(s.x() >= 18);
        assertNull(SiteSelector.find(probe, (x, z) -> 64 + Math.floorDiv(x, 2), 0, 0, 10, 5, 5, 4, 2, 500), "steep everywhere -> no site");
    }
    @Test void findGivesUpWithinTheCandidateBudget() {
        int[] calls = {0};
        TerrainProbe probe = (x, y, z) -> { calls[0]++; return y <= 63 ? SOLID : (y == 66 ? SOLID : AIR); }; // a ceiling inside every candidate volume
        assertNull(SiteSelector.find(probe, (x, z) -> 64, 0, 0, 30, 5, 5, 4, 2, 3));
        assertTrue(calls[0] < 200, "bounded work: " + calls[0]);
    }

    // ---------------------------------------------------------------- policy
    @Test void buildPolicyIsDenyByDefault() {
        assertTrue(BuildPolicy.decide(true, false, null, "OAK_PLANKS", true, true).allowed());
        assertFalse(BuildPolicy.decide(false, false, null, "OAK_PLANKS", true, true).allowed());
        assertFalse(BuildPolicy.decide(true, true, null, "OAK_PLANKS", true, true).allowed());
        assertFalse(BuildPolicy.decide(true, false, "WorldGuard", "OAK_PLANKS", true, true).allowed());
        assertFalse(BuildPolicy.decide(true, false, null, "OAK_PLANKS", false, true).allowed(), "never overwrite an occupied cell");
        assertFalse(BuildPolicy.decide(true, false, null, "OAK_PLANKS", true, false).allowed(), "never outside the project box");
        for (String m : new String[] {"TNT", "LAVA", "STONE", "CHEST", "OBSIDIAN", "COBWEB", "CRAFTING_TABLE"})
            assertFalse(BuildPolicy.decide(true, false, null, m, true, true).allowed(), m);
    }
}
