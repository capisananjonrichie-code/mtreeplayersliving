package net.mtree.livingplayers.core;

import net.mtree.livingplayers.core.Settings.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    private static WorldsCfg cfg(Set<String> allow, Set<String> deny, Map<String, WorldOverride> o) {
        return new WorldsCfg(Set.of("NORMAL"), allow, deny, o, List.of(new ProtectedRegion("spawn", "World", 10, 0, 10, -10, 255, -10)));
    }
    @Test void onlyNormalEnvironmentIsEverPopulatedInStage1() {
        WorldsCfg w = new WorldsCfg(Set.of("NORMAL", "NETHER"), Set.of(), Set.of(), Map.of(), List.of());
        assertTrue(w.populationAllowed("world", "NORMAL"));
        assertFalse(w.populationAllowed("world_nether", "NETHER"));
        assertFalse(w.populationAllowed("world_the_end", "THE_END"));
    }
    @Test void allowlistDenylistAndOverrides() {
        assertTrue(cfg(Set.of(), Set.of(), Map.of()).populationAllowed("Anything", "NORMAL"));
        assertTrue(cfg(Set.of("world"), Set.of(), Map.of()).populationAllowed("World", "NORMAL"));
        assertFalse(cfg(Set.of("world"), Set.of(), Map.of()).populationAllowed("other", "NORMAL"));
        assertFalse(cfg(Set.of(), Set.of("world"), Map.of()).populationAllowed("WORLD", "NORMAL"));
        assertFalse(cfg(Set.of(), Set.of(), Map.of("world", new WorldOverride(false, null))).populationAllowed("world", "NORMAL"));
        assertTrue(cfg(Set.of(), Set.of(), Map.of("world", new WorldOverride(null, 2))).populationAllowed("world", "NORMAL"));
    }
    @Test void maxActivePerWorld() {
        WorldsCfg w = cfg(Set.of(), Set.of(), Map.of("world", new WorldOverride(true, 2)));
        assertEquals(2, w.maxActiveFor("World"));
        assertEquals(Integer.MAX_VALUE, w.maxActiveFor("other"));
    }
    @Test void protectedRegionContainsWithSwappedCornersAndInclusiveEdges() {
        WorldsCfg w = cfg(Set.of(), Set.of(), Map.of());
        assertNotNull(w.regionAt("world", 0.5, 64, 0.5));
        assertNotNull(w.regionAt("world", 10.9, 64, 10.9), "max corner block is inside");
        assertNull(w.regionAt("world", 11.0, 64, 0), "just outside");
        assertNull(w.regionAt("other", 0, 64, 0));
    }
}
