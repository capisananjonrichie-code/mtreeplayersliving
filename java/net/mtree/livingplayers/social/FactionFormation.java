package net.mtree.livingplayers.social;

import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.bot.Trait;

/** When do bots band together? Mutual liking AND the temperament for it. Pure. */
public final class FactionFormation {
    private FactionFormation() {}

    private static boolean groupMinded(Personality p) { return p.get(Trait.SOCIABILITY) >= 0.4 && p.get(Trait.COOPERATION) >= 0.4; }

    public static boolean shouldForm(Relationship aToB, Relationship bToA, Personality a, Personality b, double minAffinity) {
        return aToB.affinity() >= minAffinity && bToA.affinity() >= minAffinity && aToB.trust() >= 30 && bToA.trust() >= 30
            && groupMinded(a) && groupMinded(b);
    }

    /** A lone bot joins an existing faction if it likes every present member it knows well enough (average) and fits in. */
    public static boolean shouldJoin(double averageAffinityToMembers, Personality joiner, double minAffinity) {
        return averageAffinityToMembers >= minAffinity - 10 && groupMinded(joiner);
    }
}
