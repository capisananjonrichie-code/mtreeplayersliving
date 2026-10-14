package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.CombatSkill;
import net.mtree.livingplayers.bot.CombatStyle;
import net.mtree.livingplayers.combat.CombatBrain.Action;
import net.mtree.livingplayers.combat.CombatBrain.Situation;
import net.mtree.livingplayers.combat.TargetSelector.Candidate;
import net.mtree.livingplayers.combat.TargetSelector.PlayerPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatBrainTest {
    static final SkillProfile MID = SkillProfile.defaults(CombatSkill.INTERMEDIATE);
    static Situation sit(double hp, boolean food, boolean retreating, double dist, double charge, int opp) {
        return new Situation(hp, food, retreating, dist, 3.0, charge, opp, MID.awarenessRange());
    }
    static Action d(Situation s, CombatStyle st) { return CombatBrain.decide(s, MID, st, new Random(1)); }

    @Test void lowHealthRetreatsOrEatsDependingOnSafety() {
        assertEquals(Action.RETREAT, d(sit(0.1, false, false, 5, 1, 1), CombatStyle.TEAM_FIGHTER));
        assertEquals(Action.RETREAT, d(sit(0.1, true, false, 4, 1, 1), CombatStyle.TEAM_FIGHTER), "enemy too close to eat");
        assertEquals(Action.EAT, d(sit(0.1, true, false, 15, 1, 1), CombatStyle.TEAM_FIGHTER));
    }
    @Test void hysteresisKeepsRetreatingUntilRecovered() {
        assertEquals(Action.RETREAT, d(sit(0.5, false, true, 10, 1, 1), CombatStyle.TEAM_FIGHTER), "0.5 < reengage 0.65");
        assertNotEquals(Action.RETREAT, d(sit(0.8, false, true, 10, 1, 1), CombatStyle.TEAM_FIGHTER), "recovered: may fight again");
    }
    @Test void outnumberedFlees() {
        assertEquals(Action.RETREAT, d(sit(1.0, true, false, 5, 1, MID.maxOpponents() + 1), CombatStyle.TEAM_FIGHTER));
        assertNotEquals(Action.RETREAT, d(sit(1.0, true, false, 5, 1, MID.maxOpponents()), CombatStyle.TEAM_FIGHTER));
    }
    @Test void hitsOnlyWhenChargedAndInReach() {
        Random never = new Random() { @Override public double nextDouble() { return 0.999; } };
        assertEquals(Action.ATTACK, CombatBrain.decide(sit(1, false, false, 2.5, 1.0, 1), MID, CombatStyle.TEAM_FIGHTER, never));
        assertNotEquals(Action.ATTACK, CombatBrain.decide(sit(1, false, false, 2.5, 0.4, 1), MID, CombatStyle.TEAM_FIGHTER, never), "uncharged: wait");
        assertEquals(Action.APPROACH, CombatBrain.decide(sit(1, false, false, 8, 1.0, 1), MID, CombatStyle.TEAM_FIGHTER, never));
    }
    @Test void disengagesFromFarTargets() {
        assertEquals(Action.DISENGAGE, d(sit(1, false, false, MID.awarenessRange() * 2, 1, 1), CombatStyle.TEAM_FIGHTER));
    }
    @Test void styleChangesRetreatThreshold() {
        // 0.30 health: a survivalist (x1.4) flees, a rusher (x0.7) keeps fighting
        assertEquals(Action.RETREAT, d(sit(0.30, false, false, 2, 1, 1), CombatStyle.DEFENSIVE_SURVIVALIST));
        assertNotEquals(Action.RETREAT, d(sit(0.30, false, false, 2, 1, 1), CombatStyle.AGGRESSIVE_SWORD_RUSHER));
    }
    @Test void everySkillStaysFallibleAndSkillImprovesMonotonically() {
        SkillProfile prev = null;
        for (CombatSkill s : CombatSkill.values()) {
            SkillProfile p = SkillProfile.defaults(s);
            assertTrue(p.reactionTicks() >= 2, s + " must still have a reaction delay");
            assertTrue(p.mistakeChance() > 0, s + " must still make mistakes");
            assertTrue(p.facingToleranceDeg() >= 5 && p.turnRateDeg() <= 45, s + " must not have instant perfect aim");
            if (prev != null) {
                assertTrue(p.reactionTicks() < prev.reactionTicks());
                assertTrue(p.mistakeChance() < prev.mistakeChance());
                assertTrue(p.facingToleranceDeg() < prev.facingToleranceDeg());
                assertTrue(p.turnRateDeg() > prev.turnRateDeg());
                assertTrue(p.critChance() > prev.critChance());
            }
            prev = p;
        }
    }
    @Test void mistakesActuallyHappenAtTheConfiguredRate() {
        SkillProfile p = SkillProfile.defaults(CombatSkill.BEGINNER);
        Random r = new Random(11);
        int attacks = 0, n = 5000;
        for (int i = 0; i < n; i++)
            if (CombatBrain.decide(new Situation(1, false, false, 2, 3, 1.0, 1, 12), p, CombatStyle.TEAM_FIGHTER, r) == Action.ATTACK) attacks++;
        double rate = 1.0 - attacks / (double) n;
        assertEquals(p.mistakeChance(), rate, 0.03);
    }

    // ---- target selection
    static Candidate c(UUID id, double dist, boolean player, boolean hostile, boolean attacked, boolean visible) {
        return new Candidate(id, dist, player, hostile, attacked, visible, 1.0, false);
    }
    @Test void neverAttacksPlayersUnprovoked() {
        Candidate p = c(UUID.randomUUID(), 3, true, false, false, true);
        assertTrue(TargetSelector.select(List.of(p), 20, PlayerPolicy.RETALIATE_ONLY, null).isEmpty());
        Candidate hitMe = c(UUID.randomUUID(), 3, true, false, true, true);
        assertTrue(TargetSelector.select(List.of(hitMe), 20, PlayerPolicy.RETALIATE_ONLY, null).isPresent());
        assertTrue(TargetSelector.select(List.of(hitMe), 20, PlayerPolicy.NEVER, null).isEmpty(), "NEVER means never");
    }
    @Test void ignoresPassiveMobsAndUnseenFarTargets() {
        assertTrue(TargetSelector.select(List.of(c(UUID.randomUUID(), 3, false, false, false, true)), 20, PlayerPolicy.NEVER, null).isEmpty(), "cow");
        assertTrue(TargetSelector.select(List.of(c(UUID.randomUUID(), 12, false, true, false, false)), 20, PlayerPolicy.NEVER, null).isEmpty(), "behind a wall");
        assertTrue(TargetSelector.select(List.of(c(UUID.randomUUID(), 4, false, true, false, false)), 20, PlayerPolicy.NEVER, null).isPresent(), "heard nearby");
        assertTrue(TargetSelector.select(List.of(c(UUID.randomUUID(), 30, false, true, false, true)), 20, PlayerPolicy.NEVER, null).isEmpty(), "beyond awareness");
    }
    @Test void prefersAttackerThenCloserAndSticksToCurrent() {
        UUID attacker = UUID.randomUUID(), near = UUID.randomUUID(), far = UUID.randomUUID();
        List<Candidate> l = List.of(c(near, 3, false, true, false, true), c(attacker, 10, false, true, true, true), c(far, 15, false, true, false, true));
        assertEquals(attacker, TargetSelector.select(l, 20, PlayerPolicy.NEVER, null).get().id());
        List<Candidate> two = List.of(c(near, 6, false, true, false, true), c(far, 7, false, true, false, true));
        assertEquals(far, TargetSelector.select(two, 20, PlayerPolicy.NEVER, far).get().id(), "stickiness beats a 1-block difference");
        assertEquals(2, TargetSelector.countThreats(two, 20, PlayerPolicy.NEVER));
        Optional<Candidate> none = TargetSelector.select(List.of(), 20, PlayerPolicy.NEVER, null);
        assertTrue(none.isEmpty());
    }
}
