package net.mtree.livingplayers.social;

/** One bot's feelings toward one other party. affinity -100..100, trust 0..100, familiarity 0..100. Immutable. */
public record Relationship(double affinity, double trust, double familiarity, long lastAt) {
    public static final Relationship NONE = new Relationship(0, 0, 0, 0);

    public Stance stance() {
        if (affinity <= -60) return Stance.HOSTILE;
        if (affinity <= -25) return Stance.RIVAL;
        if (affinity >= 55 && trust >= 40) return Stance.FRIEND;
        if (affinity >= 20) return Stance.FRIENDLY;
        return Stance.NEUTRAL;
    }

    public boolean known() { return familiarity > 0; }
}
