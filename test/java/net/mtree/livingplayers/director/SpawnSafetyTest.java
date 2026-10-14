package net.mtree.livingplayers.director;

import net.mtree.livingplayers.director.SpawnSafety.Rules;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static net.mtree.livingplayers.director.BlockClass.*;
import static org.junit.jupiter.api.Assertions.*;

class SpawnSafetyTest {
    record P(int x, int y, int z) {}

    /** Flat world: SOLID up to y=63, AIR above. Overrides applied on top. */
    static TerrainProbe flat(Map<P, BlockClass> overrides) {
        return (x, y, z) -> {
            BlockClass o = overrides.get(new P(x, y, z));
            if (o != null) return o;
            return y <= 63 ? SOLID : AIR;
        };
    }
    static final Rules R = Rules.defaults();

    @Test void flatGroundIsSafe() {
        assertTrue(SpawnSafety.check(flat(Map.of()), 0, 64, 0, R).ok());
    }
    @Test void outsideYRangeRejected() {
        assertFalse(SpawnSafety.check(flat(Map.of()), 0, 400, 0, R).ok());
        assertFalse(SpawnSafety.check(flat(Map.of()), 0, -100, 0, R).ok());
    }
    @Test void unloadedChunkIsNeverSafe() {
        TerrainProbe unknown = (x, y, z) -> UNKNOWN;
        assertEquals("chunk not loaded", SpawnSafety.check(unknown, 0, 64, 0, R).reason());
        Map<P, BlockClass> m = new HashMap<>();
        m.put(new P(1, 63, 0), UNKNOWN);
        assertFalse(SpawnSafety.check(flat(m), 0, 64, 0, R).ok());
    }
    @Test void voidAndWaterAndLavaGroundRejected() {
        Map<P, BlockClass> m = new HashMap<>();
        m.put(new P(0, 63, 0), AIR);
        assertFalse(SpawnSafety.check(flat(m), 0, 64, 0, R).ok());
        m.put(new P(0, 63, 0), WATER);
        assertFalse(SpawnSafety.check(flat(m), 0, 64, 0, R).ok());
        assertTrue(SpawnSafety.check(flat(m), 0, 64, 0, new Rules(-60, 316, true, 1, 3)).ok(), "water ground allowed only when configured");
        m.put(new P(0, 63, 0), LAVA);
        assertFalse(SpawnSafety.check(flat(m), 0, 64, 0, R).ok());
    }
    @Test void blockedHeadroomRejected() {
        assertFalse(SpawnSafety.check(flat(Map.of(new P(0, 65, 0), SOLID)), 0, 64, 0, R).ok());
        assertFalse(SpawnSafety.check(flat(Map.of(new P(0, 64, 0), WATER)), 0, 64, 0, R).ok());
    }
    @Test void passableVegetationAtFeetIsFine() {
        assertTrue(SpawnSafety.check(flat(Map.of(new P(0, 64, 0), PASSABLE)), 0, 64, 0, R).ok());
    }
    @Test void hazardNearbyRejected() {
        assertFalse(SpawnSafety.check(flat(Map.of(new P(1, 64, 1), HAZARD)), 0, 64, 0, R).ok());
        assertFalse(SpawnSafety.check(flat(Map.of(new P(-1, 63, 0), LAVA)), 0, 64, 0, R).ok());
        // hazard just outside the radius is accepted
        assertTrue(SpawnSafety.check(flat(Map.of(new P(5, 64, 5), HAZARD)), 0, 64, 0, R).ok());
    }
    @Test void playerBuiltStructuresNearbyRejected() {
        assertFalse(SpawnSafety.check(flat(Map.of(new P(3, 65, 0), ARTIFICIAL)), 0, 64, 0, R).ok());
        assertTrue(SpawnSafety.check(flat(Map.of(new P(9, 65, 0), ARTIFICIAL)), 0, 64, 0, R).ok());
        assertFalse(SpawnSafety.check(flat(Map.of(new P(0, 63, 0), ARTIFICIAL)), 0, 64, 0, R).ok());
    }
    @Test void cliffEdgeRejected() {
        TerrainProbe cliff = (x, y, z) -> {
            if (x >= 1) return y <= 40 ? SOLID : AIR; // neighbour column drops 23 blocks
            return y <= 63 ? SOLID : AIR;
        };
        assertEquals("cliff edge", SpawnSafety.check(cliff, 0, 64, 0, R).reason());
    }
    @Test void smallStepDownIsNotACliff() {
        TerrainProbe step = (x, y, z) -> {
            if (x >= 1) return y <= 61 ? SOLID : AIR;
            return y <= 63 ? SOLID : AIR;
        };
        assertTrue(SpawnSafety.check(step, 0, 64, 0, R).ok());
    }
    @Test void lavaBelowEdgeRejected() {
        TerrainProbe lavaPit = (x, y, z) -> {
            if (x >= 1) return y <= 58 ? LAVA : AIR;
            return y <= 63 ? SOLID : AIR;
        };
        assertFalse(SpawnSafety.check(lavaPit, 0, 64, 0, R).ok());
    }
}
