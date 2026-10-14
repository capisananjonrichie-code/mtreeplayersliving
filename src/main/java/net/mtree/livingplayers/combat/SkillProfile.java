package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.CombatSkill;

/**
 * What a skill level changes. Every level stays fallible: even EXPERT has a reaction delay, a turn-rate limit, a facing
 * tolerance and a non-zero mistake chance, so no bot is omniscient or impossible to beat.
 *
 * @param reactionTicks     delay before reacting to a new target or to taking damage
 * @param facingToleranceDeg max angle between where it looks and where the target is for a hit to land
 * @param turnRateDeg       max degrees it can turn per tick
 * @param mistakeChance     chance to hesitate/whiff instead of attacking when ready
 * @param critChance        chance to attempt a jump-crit each swing
 * @param strafeChance      chance to sidestep instead of standing
 * @param eatBelow          health fraction below which it eats (if safe)
 * @param retreatBelow      health fraction below which it flees
 * @param reengageAbove     health fraction it must recover to before fighting again
 * @param maxOpponents      more simultaneous enemies than this makes it flee
 * @param awarenessRange    blocks within which it notices enemies
 */
public record SkillProfile(int reactionTicks, double facingToleranceDeg, double turnRateDeg, double mistakeChance,
                           double critChance, double strafeChance, double eatBelow, double retreatBelow,
                           double reengageAbove, int maxOpponents, double awarenessRange) {

    public static SkillProfile defaults(CombatSkill s) {
        return switch (s) {
            case BEGINNER -> new SkillProfile(14, 40, 14, 0.25, 0.02, 0.10, 0.35, 0.25, 0.70, 1, 12);
            case INTERMEDIATE -> new SkillProfile(9, 25, 20, 0.14, 0.12, 0.30, 0.45, 0.30, 0.65, 2, 16);
            case ADVANCED -> new SkillProfile(5, 14, 28, 0.07, 0.30, 0.50, 0.50, 0.30, 0.60, 3, 20);
            case EXPERT -> new SkillProfile(3, 8, 35, 0.03, 0.55, 0.70, 0.55, 0.30, 0.60, 3, 24);
        };
    }
}
