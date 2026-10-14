package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.CombatStyle;

import java.util.Random;

/** Pure fight/flee/eat decision. Inputs are what a player could know; there is no hidden information. */
public final class CombatBrain {
    private CombatBrain() {}

    public enum Action { ATTACK, APPROACH, STRAFE, EAT, RETREAT, DISENGAGE }

    public record Situation(double healthFrac, boolean hasFood, boolean currentlyRetreating, double targetDistance,
                            double reach, double chargeFrac, int opponents, double awarenessRange) {}

    /** How a fighting style bends the skill profile. */
    public record Mods(double retreat, double strafe) {}

    public static Mods mods(CombatStyle style) {
        return switch (style) {
            case AGGRESSIVE_SWORD_RUSHER -> new Mods(0.7, 0.6);
            case DEFENSIVE_AXE_FIGHTER -> new Mods(1.1, 0.8);
            case SHIELD_SPECIALIST -> new Mods(1.0, 1.0);
            case RANGED_SPECIALIST -> new Mods(1.2, 1.2);
            case MACE_WIND_CHARGE_FIGHTER -> new Mods(0.6, 0.7);
            case OPPORTUNISTIC_AMBUSHER -> new Mods(1.0, 1.3);
            case DEFENSIVE_SURVIVALIST -> new Mods(1.4, 0.8);
            case TEAM_FIGHTER -> new Mods(1.0, 1.0);
        };
    }

    public static Action decide(Situation s, SkillProfile p, CombatStyle style, Random rnd) {
        Mods m = mods(style);
        double retreatAt = Math.min(0.9, p.retreatBelow() * m.retreat());
        boolean safeToEat = s.hasFood() && s.targetDistance() > s.reach() * 3;

        if (s.currentlyRetreating() && s.healthFrac() < p.reengageAbove())
            return safeToEat && s.healthFrac() < 0.9 ? Action.EAT : Action.RETREAT; // hysteresis: recover before re-engaging
        if (s.healthFrac() <= retreatAt) return safeToEat ? Action.EAT : Action.RETREAT;
        if (s.opponents() > p.maxOpponents()) return Action.RETREAT; // outmatched
        if (s.healthFrac() <= p.eatBelow() && s.hasFood() && s.targetDistance() > s.reach() * 2) return Action.EAT;
        if (s.targetDistance() > s.awarenessRange() * 1.5) return Action.DISENGAGE;

        if (s.targetDistance() <= s.reach()) {
            if (s.chargeFrac() >= 0.9) return rnd.nextDouble() < p.mistakeChance() ? Action.STRAFE : Action.ATTACK;
            return rnd.nextDouble() < Math.min(1.0, p.strafeChance() * m.strafe()) ? Action.STRAFE : Action.APPROACH;
        }
        return Action.APPROACH;
    }
}
