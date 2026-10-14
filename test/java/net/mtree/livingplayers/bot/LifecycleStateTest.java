package net.mtree.livingplayers.bot;

import org.junit.jupiter.api.Test;

import static net.mtree.livingplayers.bot.LifecycleState.*;
import static org.junit.jupiter.api.Assertions.*;

class LifecycleStateTest {
    @Test void activeCanGoAnywhere() {
        for (LifecycleState s : values()) assertTrue(ACTIVE.canTransitionTo(s));
    }
    @Test void disabledOnlyReturnsToInactive() {
        assertTrue(DISABLED.canTransitionTo(INACTIVE));
        assertFalse(DISABLED.canTransitionTo(ACTIVE));
        assertFalse(DISABLED.canTransitionTo(RESPAWNING));
    }
    @Test void deadMustRespawnOrBeRevivedInactive() {
        assertTrue(DEAD.canTransitionTo(RESPAWNING));
        assertTrue(DEAD.canTransitionTo(INACTIVE));
        assertFalse(DEAD.canTransitionTo(ACTIVE));
    }
    @Test void inactiveCannotBecomeUnloadedOrDead() {
        assertFalse(INACTIVE.canTransitionTo(UNLOADED));
        assertFalse(INACTIVE.canTransitionTo(DEAD));
    }
    @Test void onlyActiveExpectsEntityAndOnlyUnloadedAutoReactivates() {
        for (LifecycleState s : values()) {
            assertEquals(s == ACTIVE, s.expectsEntity());
            assertEquals(s == UNLOADED, s.autoReactivates());
        }
    }
    @Test void parseFallsBack() {
        assertEquals(DEAD, parse(" dead ", INACTIVE));
        assertEquals(INACTIVE, parse("nonsense", INACTIVE));
        assertEquals(INACTIVE, parse(null, INACTIVE));
    }
}
