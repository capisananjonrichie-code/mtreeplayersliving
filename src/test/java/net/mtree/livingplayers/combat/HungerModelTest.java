package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.combat.HungerModel.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HungerModelTest {
    @Test void fullHealthNeverChangesAnything() {
        State s = State.of(20, 5f);
        for (int i = 0; i < 1000; i++) { Result r = HungerModel.tick(s, 20, 20, true); assertEquals(0, r.healthDelta()); s = r.state(); }
        assertEquals(20, s.hunger());
    }
    @Test void healingIsNotFree_regenerationStopsWhenFoodRunsOutAndResumesAfterEating() {
        State s = State.of(20, 5f);
        double hp = 1.0;
        for (int i = 0; i < 100_000; i++) { Result r = HungerModel.tick(s, hp, 20, true); hp += r.healthDelta(); s = r.state(); }
        assertTrue(hp > 1.0, "it heals while fed");
        assertTrue(hp < 20.0, "but 20 hunger + 5 saturation cannot heal 19 HP for free (healed to " + hp + ")");
        assertTrue(s.hunger() < 18, "regeneration consumed the food");
        double stalled = hp;
        for (int i = 0; i < 2_000; i++) { Result r = HungerModel.tick(s, hp, 20, true); hp += r.healthDelta(); s = r.state(); }
        assertTrue(hp <= stalled + 1e-9, "no regeneration once hunger is low");
        s = HungerModel.eat(s, 8, 12.8f);
        for (int i = 0; i < 100_000 && hp < 20; i++) { Result r = HungerModel.tick(s, hp, 20, true); hp += r.healthDelta(); s = r.state(); }
        assertTrue(hp > stalled, "eating lets healing continue");
    }
    @Test void cannotRegenerateBelowHungerThreshold() {
        State s = State.of(17, 0f);
        for (int i = 0; i < 2000; i++) { Result r = HungerModel.tick(s, 10, 20, true); assertTrue(r.healthDelta() <= 0); s = r.state(); }
    }
    @Test void starvationDamagesOnHardAndStopsAtOneHpOnEasier() {
        State s = State.of(0, 0f);
        double hp = 10;
        for (int i = 0; i < 80 * 3; i++) { Result r = HungerModel.tick(s, hp, 20, true); hp += r.healthDelta(); s = r.state(); }
        assertEquals(7.0, hp, 1e-9, "Hard: 1 HP per 80 ticks");
        State e = State.of(0, 0f);
        double hp2 = 2;
        for (int i = 0; i < 80 * 10; i++) { Result r = HungerModel.tick(e, hp2, 20, false); hp2 += r.healthDelta(); e = r.state(); }
        assertEquals(1.0, hp2, 1e-9, "non-Hard difficulty never starves a bot to death");
    }
    @Test void exhaustionDrainsSaturationThenHunger() {
        State s = State.of(20, 2f);
        s = HungerModel.addExhaustion(s, 4.5f);
        s = HungerModel.tick(s, 20, 20, true).state();
        assertEquals(1f, s.saturation(), 1e-6);
        s = HungerModel.addExhaustion(s, 8f);
        s = HungerModel.tick(s, 20, 20, true).state();
        s = HungerModel.tick(HungerModel.addExhaustion(s, 4.5f), 20, 20, true).state();
        assertTrue(s.hunger() < 20);
    }
    @Test void eatingRestoresButCaps() {
        State s = HungerModel.eat(State.of(10, 0f), 8, 12.8f);
        assertEquals(18, s.hunger());
        assertEquals(12.8f, s.saturation(), 1e-5);
        State c = HungerModel.eat(State.of(0, 18f), 1, 5f);
        assertEquals(1, c.hunger());
        assertEquals(1f, c.saturation(), 1e-6, "saturation can never exceed hunger");
        assertEquals(20, HungerModel.eat(State.of(19, 0f), 8, 1f).hunger());
    }
}
