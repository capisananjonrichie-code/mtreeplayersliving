package net.mtree.livingplayers.bot;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class PersonalityGeneratorTest {
    @Test void deterministicForSameSeed() {
        Personality a = PersonalityGenerator.generate(new Random(42), PersonalityGenerator.DEFAULTS);
        Personality b = PersonalityGenerator.generate(new Random(42), PersonalityGenerator.DEFAULTS);
        assertEquals(a, b);
    }
    @Test void traitsAlwaysWithinUnitRange() {
        Random r = new Random(1);
        for (int i = 0; i < 500; i++) {
            Personality p = PersonalityGenerator.generate(r, null);
            for (Trait t : Trait.values()) assertTrue(p.get(t) >= 0 && p.get(t) <= 1, t + "=" + p.get(t));
        }
    }
    @Test void personalityClampsAndFillsDefaults() {
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        m.put(Trait.AGGRESSION, 5.0);
        m.put(Trait.CAUTION, -3.0);
        m.put(Trait.COURAGE, Double.NaN);
        Personality p = new Personality(m);
        assertEquals(1.0, p.get(Trait.AGGRESSION));
        assertEquals(0.0, p.get(Trait.CAUTION));
        assertEquals(0.5, p.get(Trait.COURAGE));
        assertEquals(0.5, p.get(Trait.CURIOSITY));
    }
    @Test void aggressivePersonalitiesMostlyPickRushStyle() {
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) m.put(t, 0.2);
        m.put(Trait.AGGRESSION, 1.0); m.put(Trait.COURAGE, 1.0); m.put(Trait.RISK_TOLERANCE, 0.8);
        Personality p = new Personality(m);
        Random r = new Random(7);
        int rush = 0;
        for (int i = 0; i < 1000; i++) if (PersonalityGenerator.pickStyle(p, r) == CombatStyle.AGGRESSIVE_SWORD_RUSHER) rush++;
        assertTrue(rush > 300, "rush picked " + rush + "/1000");
    }
    @Test void cautiousPersonalitiesRarelyPickRush() {
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) m.put(t, 0.3);
        m.put(Trait.CAUTION, 1.0); m.put(Trait.AGGRESSION, 0.0); m.put(Trait.COURAGE, 0.1); m.put(Trait.RISK_TOLERANCE, 0.0);
        Personality p = new Personality(m);
        Random r = new Random(7);
        int rush = 0;
        for (int i = 0; i < 1000; i++) if (PersonalityGenerator.pickStyle(p, r) == CombatStyle.AGGRESSIVE_SWORD_RUSHER) rush++;
        assertTrue(rush < 100, "rush picked " + rush + "/1000");
    }
    @Test void goalsAreDistinctAndRespectCount() {
        Personality p = PersonalityGenerator.generate(new Random(3), null);
        List<LongTermGoal> g = PersonalityGenerator.pickGoals(p, new Random(3), 3);
        assertEquals(3, g.size());
        assertEquals(3, g.stream().distinct().count());
        assertEquals(1, PersonalityGenerator.pickGoals(p, new Random(3), 0).size());
        assertEquals(LongTermGoal.values().length, PersonalityGenerator.pickGoals(p, new Random(3), 999).size());
    }
    @Test void builderPersonalitiesPreferBuildingGoals() {
        Map<Trait, Double> m = new EnumMap<>(Trait.class);
        for (Trait t : Trait.values()) m.put(t, 0.2);
        m.put(Trait.BUILDING_PREFERENCE, 1.0); m.put(Trait.RESOURCEFULNESS, 0.9); m.put(Trait.CAUTION, 0.8);
        Personality p = new Personality(m);
        assertTrue(PersonalityGenerator.goalWeight(LongTermGoal.CREATE_FARM, p) > PersonalityGenerator.goalWeight(LongTermGoal.BECOME_PVP_FIGHTER, p));
    }
    @Test void skillPickFollowsWeights() {
        Random r = new Random(5);
        int expert = 0, beginner = 0;
        for (int i = 0; i < 4000; i++) {
            CombatSkill s = PersonalityGenerator.pickSkill(r, null);
            if (s == CombatSkill.EXPERT) expert++;
            if (s == CombatSkill.BEGINNER) beginner++;
        }
        assertTrue(beginner > expert * 4, "beginner=" + beginner + " expert=" + expert);
        assertTrue(expert > 0);
    }
    @Test void zeroWeightSkillNeverPicked() {
        Map<CombatSkill, Double> w = new EnumMap<>(CombatSkill.class);
        w.put(CombatSkill.BEGINNER, 1.0); w.put(CombatSkill.EXPERT, 0.0);
        Random r = new Random(2);
        for (int i = 0; i < 500; i++) assertEquals(CombatSkill.BEGINNER, PersonalityGenerator.pickSkill(r, w));
    }
}
