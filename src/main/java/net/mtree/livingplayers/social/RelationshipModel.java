package net.mtree.livingplayers.social;

import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.bot.Trait;

/** Pure relationship arithmetic. Personality changes how strongly a bot reacts; it never changes the direction. */
public final class RelationshipModel {
    private RelationshipModel() {}

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    public static Relationship apply(Relationship r, SocialEvent e, Personality p, long now) {
        double a = e.affinity, t = e.trust;
        if (a > 0) { double m = 0.7 + 0.6 * p.get(Trait.FRIENDLINESS); a *= m; t *= m; }            // friendly bots warm up faster
        else { double m = 0.6 + 0.8 * p.get(Trait.AGGRESSION); a *= m; t *= m; }                  // aggressive bots hold bigger grudges
        return new Relationship(clamp(r.affinity() + a, -100, 100), clamp(r.trust() + t, 0, 100),
            clamp(r.familiarity() + 1, 0, 100), now);
    }

    /** Feelings drift toward neutral with the given half-life; familiarity is remembered. */
    public static Relationship decay(Relationship r, long now, long halfLifeMs) {
        if (r.lastAt() <= 0 || now <= r.lastAt() || halfLifeMs <= 0) return r;
        double f = Math.pow(0.5, (now - r.lastAt()) / (double) halfLifeMs);
        return new Relationship(r.affinity() * f, r.trust() * f, r.familiarity(), now);
    }
}
