package net.mtree.livingplayers.director;

import net.mtree.livingplayers.director.PopulationPlanner.Action;
import net.mtree.livingplayers.director.PopulationPlanner.Input;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DirectorLogicTest {
    private static Input in(boolean en, int players, int active, int max, double tps) {
        return new Input(en, players, active, max, 1.0, tps, 17.0);
    }

    @Test void targetScalesWithPlayersAndIsCapped() {
        assertEquals(0, PopulationPlanner.targetActive(0, 1.0, 4));
        assertEquals(2, PopulationPlanner.targetActive(2, 1.0, 4));
        assertEquals(3, PopulationPlanner.targetActive(2, 1.5, 4));
        assertEquals(4, PopulationPlanner.targetActive(10, 1.0, 4));
        assertEquals(0, PopulationPlanner.targetActive(3, 0, 4));
    }
    @Test void plansSpawnWhenBelowTarget() {
        assertEquals(Action.SPAWN, PopulationPlanner.plan(in(true, 2, 0, 4, 20)).action());
    }
    @Test void plansDespawnWhenAboveTarget() {
        assertEquals(Action.DESPAWN_ONE, PopulationPlanner.plan(in(true, 1, 3, 4, 20)).action());
    }
    @Test void holdsAtTarget() {
        assertEquals(Action.NONE, PopulationPlanner.plan(in(true, 2, 2, 4, 20)).action());
    }
    @Test void doesNothingWhenDisabledOrNoPlayersOrLowTps() {
        assertEquals(Action.NONE, PopulationPlanner.plan(in(false, 2, 0, 4, 20)).action());
        assertEquals(Action.NONE, PopulationPlanner.plan(in(true, 0, 0, 4, 20)).action());
        Action a = PopulationPlanner.plan(in(true, 2, 0, 4, 12.0)).action();
        assertEquals(Action.NONE, a, "low TPS must stop new spawns");
    }
    @Test void encounterCooldownsAreIndependentPerPairAndPerBot() {
        EncounterTracker t = new EncounterTracker();
        UUID p1 = UUID.randomUUID(), p2 = UUID.randomUUID(), b = UUID.randomUUID();
        assertTrue(t.pairAllowed(p1, b, 0, 1000));
        t.record(p1, b, 100);
        assertFalse(t.pairAllowed(p1, b, 500, 1000));
        assertTrue(t.pairAllowed(p2, b, 500, 1000), "different player is a different pair");
        assertFalse(t.botAllowed(b, 500, 1000), "bot-level cooldown still applies");
        assertTrue(t.pairAllowed(p1, b, 1100, 1000));
        assertTrue(t.botAllowed(b, 1100, 1000));
    }
    @Test void trackerPruneAndForget() {
        EncounterTracker t = new EncounterTracker();
        UUID p = UUID.randomUUID(), b = UUID.randomUUID();
        t.record(p, b, 0);
        t.prune(10_000, 5_000);
        assertEquals(0, t.size());
        t.record(p, b, 0);
        t.forget(b);
        assertTrue(t.pairAllowed(p, b, 1, 1_000_000));
    }
    @Test void annulusRespectsMinAndMaxDistance() {
        Random r = new Random(123);
        for (int i = 0; i < 5000; i++) {
            Annulus.Offset o = Annulus.sample(r, 48, 96);
            double d = Math.hypot(o.dx(), o.dz());
            assertTrue(d >= 47.0 && d <= 97.0, "distance " + d);
        }
    }
    @Test void annulusHandlesSwappedAndZeroBounds() {
        Random r = new Random(1);
        Annulus.Offset o = Annulus.sample(r, 96, 48);
        assertTrue(Math.hypot(o.dx(), o.dz()) >= 47);
        assertEquals(0, Annulus.sample(r, 0, 0).dx());
    }
}
