package net.mtree.livingplayers.nav;

import net.mtree.livingplayers.director.BlockClass;
import net.mtree.livingplayers.director.TerrainProbe;
import net.mtree.livingplayers.nav.Pathfinder.*;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static net.mtree.livingplayers.director.BlockClass.*;
import static org.junit.jupiter.api.Assertions.*;

class PathfinderTest {
    record P(int x, int y, int z) {}

    /** Flat world: SOLID for y<=63 (so feet y=64), AIR above. Overrides win. */
    static TerrainProbe world(Map<P, BlockClass> o) {
        return (x, y, z) -> { BlockClass c = o.get(new P(x, y, z)); return c != null ? c : (y <= 63 ? SOLID : AIR); };
    }
    static void wallX(Map<P, BlockClass> m, int x, int zFrom, int zTo, int yFrom, int yTo) {
        for (int z = zFrom; z <= zTo; z++) for (int y = yFrom; y <= yTo; y++) m.put(new P(x, y, z), SOLID);
    }
    static final Limits L = Limits.defaults();

    @Test void straightLineOnFlatGround() {
        PathResult r = Pathfinder.find(world(Map.of()), new Node(0, 64, 0), new Node(10, 64, 0), L);
        assertEquals(Status.FOUND, r.status());
        assertEquals(new Node(0, 64, 0), r.path().get(0));
        assertEquals(new Node(10, 64, 0), r.path().get(r.path().size() - 1));
        assertEquals(11, r.path().size());
    }
    @Test void diagonalIsShorterThanManhattan() {
        PathResult r = Pathfinder.find(world(Map.of()), new Node(0, 64, 0), new Node(6, 64, 6), L);
        assertEquals(Status.FOUND, r.status());
        assertEquals(7, r.path().size());
    }
    @Test void routesAroundAWallThroughAGap() {
        Map<P, BlockClass> m = new HashMap<>();
        wallX(m, 5, -10, 10, 64, 65);
        for (int y = 64; y <= 65; y++) m.remove(new P(5, y, 8)); // gap at z=8
        PathResult r = Pathfinder.find(world(m), new Node(0, 64, 0), new Node(10, 64, 0), L);
        assertEquals(Status.FOUND, r.status());
        assertTrue(r.path().stream().anyMatch(n -> n.x() == 5 && n.z() == 8), "must pass through the gap");
        assertTrue(r.path().stream().noneMatch(n -> n.x() == 5 && n.z() != 8));
    }
    @Test void fullyEnclosedGoalHasNoUsableCompletePath() {
        Map<P, BlockClass> m = new HashMap<>();
        for (int x = 8; x <= 12; x++) for (int z = -2; z <= 2; z++)
            if (x == 8 || x == 12 || z == -2 || z == 2) for (int y = 64; y <= 66; y++) m.put(new P(x, y, z), SOLID);
        PathResult r = Pathfinder.find(world(m), new Node(0, 64, 0), new Node(10, 64, 0), new Limits(3000, 48, 3));
        assertNotEquals(Status.FOUND, r.status());
    }
    @Test void stepsUpOneBlockButNotTwo() {
        Map<P, BlockClass> m = new HashMap<>();
        m.put(new P(3, 64, 0), SOLID); // 1-high ledge
        PathResult up = Pathfinder.find(world(m), new Node(0, 64, 0), new Node(3, 65, 0), L);
        assertEquals(Status.FOUND, up.status());
        Map<P, BlockClass> two = new HashMap<>();
        for (int z = -20; z <= 20; z++) { two.put(new P(3, 64, z), SOLID); two.put(new P(3, 65, z), SOLID); }
        PathResult blocked = Pathfinder.find(world(two), new Node(0, 64, 0), new Node(4, 66, 0), L);
        assertNotEquals(Status.FOUND, blocked.status());
    }
    @Test void dropsUpToMaxDropOnly() {
        // Plateau x<=0 at y=63 ground; x>=1 ground is 3 lower (safe) or 6 lower (too deep)
        TerrainProbe safe = (x, y, z) -> { int top = x <= 0 ? 63 : 60; return y <= top ? SOLID : AIR; };
        assertEquals(Status.FOUND, Pathfinder.find(safe, new Node(0, 64, 0), new Node(2, 61, 0), L).status());
        TerrainProbe deep = (x, y, z) -> { int top = x <= 0 ? 63 : 57; return y <= top ? SOLID : AIR; };
        PathResult r = Pathfinder.find(deep, new Node(0, 64, 0), new Node(2, 58, 0), L);
        assertNotEquals(Status.FOUND, r.status(), "a 6-block drop must never be planned");
    }
    @Test void neverEntersWaterLavaHazardOrUnknown() {
        for (BlockClass bad : new BlockClass[] {WATER, LAVA, HAZARD, UNKNOWN}) {
            Map<P, BlockClass> m = new HashMap<>();
            for (int z = -60; z <= 60; z++) m.put(new P(3, 63, z), bad); // barrier longer than maxDistance: no detour possible
            PathResult r = Pathfinder.find(world(m), new Node(0, 64, 0), new Node(6, 64, 0), L);
            assertNotEquals(Status.FOUND, r.status(), bad + " must block the route");
            assertTrue(r.path().stream().noneMatch(n -> n.x() == 3), bad + " column was entered");
        }
    }
    @Test void doesNotCutWallCorners() {
        Map<P, BlockClass> m = new HashMap<>();
        for (int y = 64; y <= 65; y++) { m.put(new P(1, y, 0), SOLID); m.put(new P(0, y, 1), SOLID); }
        PathResult r = Pathfinder.find(world(m), new Node(0, 64, 0), new Node(1, 64, 1), L);
        // the only diagonal (0,0)->(1,1) squeezes between two wall blocks and must be refused
        assertFalse(r.path().size() == 2, "diagonal squeeze must not be allowed");
    }
    @Test void invalidStartAndGoalAreReported() {
        TerrainProbe w = world(Map.of());
        assertEquals(Status.START_INVALID, Pathfinder.find(w, new Node(0, 70, 0), new Node(5, 64, 0), L).status());
        assertEquals(Status.GOAL_INVALID, Pathfinder.find(w, new Node(0, 64, 0), new Node(5, 70, 0), L).status());
    }
    @Test void budgetExhaustionReturnsPartialProgressNotAHang() {
        PathResult r = Pathfinder.find(world(Map.of()), new Node(0, 64, 0), new Node(45, 64, 45), new Limits(40, 48, 3));
        assertEquals(Status.PARTIAL, r.status());
        assertTrue(r.expanded() <= 40);
        assertTrue(r.usable());
        assertNotEquals(new Node(0, 64, 0), r.path().get(r.path().size() - 1), "partial path must make progress");
    }
    @Test void maxDistanceBoundsTheSearch() {
        PathResult r = Pathfinder.find(world(Map.of()), new Node(0, 64, 0), new Node(100, 64, 0), new Limits(5000, 20, 3));
        assertNotEquals(Status.FOUND, r.status());
        assertTrue(r.path().stream().allMatch(n -> Math.abs(n.x()) <= 20));
    }
    @Test void startEqualsGoal() {
        assertEquals(List.of(new Node(0, 64, 0)), Pathfinder.find(world(Map.of()), new Node(0, 64, 0), new Node(0, 64, 0), L).path());
    }
    @Test void everyStepOfEveryPathIsAdjacentAndStandable() {
        Map<P, BlockClass> m = new HashMap<>();
        wallX(m, 4, -6, 6, 64, 66);
        m.put(new P(8, 64, 2), SOLID);
        TerrainProbe w = world(m);
        PathResult r = Pathfinder.find(w, new Node(0, 64, 0), new Node(12, 64, 3), L);
        assertEquals(Status.FOUND, r.status());
        for (int i = 0; i < r.path().size(); i++) {
            Node n = r.path().get(i);
            assertTrue(Pathfinder.standable(w, n.x(), n.y(), n.z()), "node " + n);
            if (i > 0) {
                Node a = r.path().get(i - 1);
                assertTrue(Math.abs(n.x() - a.x()) <= 1 && Math.abs(n.z() - a.z()) <= 1, "gap between " + a + " and " + n);
            }
        }
    }
}
