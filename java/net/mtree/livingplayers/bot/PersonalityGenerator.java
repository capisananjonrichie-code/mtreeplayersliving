package net.mtree.livingplayers.bot;

import net.mtree.livingplayers.bot.PersonalityTemplate.Range;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.ToDoubleFunction;

/** Pure, seedable generation of personalities, combat styles, skills and long-term goals. */
public final class PersonalityGenerator {
    private PersonalityGenerator() {}

    private static PersonalityTemplate t(String id, double w, Object... kv) {
        Map<Trait, Range> r = new EnumMap<>(Trait.class);
        for (int i = 0; i < kv.length; i += 3) r.put((Trait) kv[i], new Range((double) kv[i + 1], (double) kv[i + 2]));
        return new PersonalityTemplate(id, w, r);
    }

    /** Built-in fallback archetypes (personalities.yml normally supplies these). */
    public static final List<PersonalityTemplate> DEFAULTS = List.of(
        t("brawler", 1.0, Trait.AGGRESSION, 0.6, 0.95, Trait.COURAGE, 0.6, 0.95, Trait.CAUTION, 0.05, 0.4, Trait.COMBAT_PREFERENCE, 0.6, 0.95),
        t("builder", 1.0, Trait.BUILDING_PREFERENCE, 0.7, 1.0, Trait.RESOURCEFULNESS, 0.6, 0.9, Trait.AGGRESSION, 0.0, 0.3),
        t("explorer", 1.0, Trait.CURIOSITY, 0.7, 1.0, Trait.RISK_TOLERANCE, 0.5, 0.9),
        t("trader", 0.8, Trait.SOCIABILITY, 0.6, 0.95, Trait.FRIENDLINESS, 0.6, 0.95, Trait.COOPERATION, 0.6, 0.95),
        t("survivalist", 1.0, Trait.CAUTION, 0.6, 0.9, Trait.RESOURCEFULNESS, 0.6, 0.9),
        t("loner", 0.6, Trait.SOCIABILITY, 0.0, 0.3, Trait.CURIOSITY, 0.4, 0.8)
    );

    public static Personality generate(Random rnd, List<PersonalityTemplate> templates) {
        List<PersonalityTemplate> pool = (templates == null || templates.isEmpty()) ? DEFAULTS : templates;
        PersonalityTemplate chosen = weightedPick(rnd, pool, PersonalityTemplate::weight);
        Map<Trait, Double> out = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            Range r = chosen.ranges().get(trait);
            double lo = r != null ? r.min() : 0.1, hi = r != null ? r.max() : 0.9;
            out.put(trait, lo + rnd.nextDouble() * (hi - lo));
        }
        return new Personality(out);
    }

    public static CombatStyle pickStyle(Personality p, Random rnd) {
        Map<CombatStyle, Double> w = new EnumMap<>(CombatStyle.class);
        double ag = p.get(Trait.AGGRESSION), co = p.get(Trait.COURAGE), ca = p.get(Trait.CAUTION),
               ri = p.get(Trait.RISK_TOLERANCE), so = p.get(Trait.SOCIABILITY), cu = p.get(Trait.CURIOSITY),
               cp = p.get(Trait.COMBAT_PREFERENCE), cr = p.get(Trait.COOPERATION);
        w.put(CombatStyle.AGGRESSIVE_SWORD_RUSHER, ag * 0.6 + co * 0.3 + ri * 0.1);
        w.put(CombatStyle.DEFENSIVE_AXE_FIGHTER, ca * 0.5 + co * 0.3 + (1 - ag) * 0.2);
        w.put(CombatStyle.SHIELD_SPECIALIST, ca * 0.6 + (1 - ri) * 0.4);
        w.put(CombatStyle.RANGED_SPECIALIST, ca * 0.3 + cu * 0.2 + cp * 0.5);
        w.put(CombatStyle.MACE_WIND_CHARGE_FIGHTER, ri * 0.6 + ag * 0.2 + cu * 0.2);
        w.put(CombatStyle.OPPORTUNISTIC_AMBUSHER, ca * 0.3 + ri * 0.2 + (1 - so) * 0.5);
        w.put(CombatStyle.DEFENSIVE_SURVIVALIST, ca * 0.7 + (1 - cp) * 0.3);
        w.put(CombatStyle.TEAM_FIGHTER, so * 0.5 + cr * 0.5);
        // squaring sharpens the preference without making the choice deterministic
        List<Map.Entry<CombatStyle, Double>> entries = new ArrayList<>(w.entrySet());
        return weightedPick(rnd, entries, e -> e.getValue() * e.getValue() + 0.001).getKey();
    }

    public static double goalWeight(LongTermGoal g, Personality p) {
        double ag = p.get(Trait.AGGRESSION), co = p.get(Trait.COURAGE), ca = p.get(Trait.CAUTION),
               cu = p.get(Trait.CURIOSITY), so = p.get(Trait.SOCIABILITY), re = p.get(Trait.RESOURCEFULNESS),
               bu = p.get(Trait.BUILDING_PREFERENCE), cp = p.get(Trait.COMBAT_PREFERENCE),
               cr = p.get(Trait.COOPERATION), ri = p.get(Trait.RISK_TOLERANCE), fr = p.get(Trait.FRIENDLINESS);
        double w = switch (g) {
            case BECOME_PVP_FIGHTER -> ag * 0.4 + cp * 0.4 + co * 0.2;
            case ESTABLISH_VILLAGE -> bu * 0.4 + so * 0.3 + cr * 0.3;
            case BUILD_HIDDEN_BASE -> bu * 0.4 + ca * 0.3 + (1 - so) * 0.3;
            case COLLECT_DIAMONDS -> re * 0.4 + ri * 0.3 + cu * 0.3;
            case EXPLORE_ANCIENT_STRUCTURES -> cu * 0.5 + ri * 0.3 + co * 0.2;
            case BECOME_TRADER -> so * 0.4 + fr * 0.3 + cr * 0.3;
            case CREATE_FARM -> re * 0.4 + bu * 0.3 + ca * 0.3;
            case DEFEND_TERRITORY -> co * 0.4 + cp * 0.3 + cr * 0.3;
            case EXPLORE_CAVES -> cu * 0.4 + ri * 0.4 + co * 0.2;
            case COLLECT_RARE_ITEMS -> re * 0.3 + cu * 0.4 + ri * 0.3;
            case FORM_GROUP -> so * 0.5 + fr * 0.3 + cr * 0.2;
            case HUNT_DANGEROUS_MOBS -> co * 0.4 + cp * 0.3 + ri * 0.3;
        };
        return w * w + 0.01;
    }

    public static List<LongTermGoal> pickGoals(Personality p, Random rnd, int count) {
        List<LongTermGoal> remaining = new ArrayList<>(List.of(LongTermGoal.values()));
        List<LongTermGoal> out = new ArrayList<>();
        int n = Math.max(1, Math.min(count, remaining.size()));
        while (out.size() < n) {
            LongTermGoal g = weightedPick(rnd, remaining, x -> goalWeight(x, p));
            out.add(g);
            remaining.remove(g);
        }
        return out;
    }

    public static CombatSkill pickSkill(Random rnd, Map<CombatSkill, Double> weights) {
        Map<CombatSkill, Double> w = (weights == null || weights.isEmpty()) ? defaultSkillWeights() : weights;
        return weightedPick(rnd, new ArrayList<>(w.entrySet()), e -> Math.max(0, e.getValue())).getKey();
    }

    public static Map<CombatSkill, Double> defaultSkillWeights() {
        Map<CombatSkill, Double> m = new LinkedHashMap<>();
        m.put(CombatSkill.BEGINNER, 50.0);
        m.put(CombatSkill.INTERMEDIATE, 30.0);
        m.put(CombatSkill.ADVANCED, 15.0);
        m.put(CombatSkill.EXPERT, 5.0);
        return m;
    }

    static <T> T weightedPick(Random rnd, List<T> items, ToDoubleFunction<T> weight) {
        double total = 0;
        for (T i : items) total += Math.max(0, weight.applyAsDouble(i));
        if (total <= 0) return items.get(rnd.nextInt(items.size()));
        double roll = rnd.nextDouble() * total, acc = 0;
        for (T i : items) {
            acc += Math.max(0, weight.applyAsDouble(i));
            if (roll < acc) return i;
        }
        return items.get(items.size() - 1);
    }
}
