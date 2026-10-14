package net.mtree.livingplayers.combat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Picks who to fight. Hostile mobs are always fair game; REAL PLAYERS only in retaliation (or never), so bots do not
 * constantly attack the people they share a server with. Targets it cannot see are ignored beyond hearing distance.
 */
public final class TargetSelector {
    private TargetSelector() {}

    public enum PlayerPolicy { NEVER, RETALIATE_ONLY }

    public record Candidate(UUID id, double distance, boolean player, boolean hostileMob, boolean attackedMeRecently,
                            boolean visible, double healthFrac, boolean rival) {}

    public static final double HEARING_DISTANCE = 5.0;

    public static boolean eligible(Candidate c, double awareness, PlayerPolicy policy) {
        if (c.distance() > awareness) return false;
        if (!c.visible() && c.distance() > HEARING_DISTANCE) return false;
        if (c.player()) return policy == PlayerPolicy.RETALIATE_ONLY && c.attackedMeRecently();
        return c.hostileMob() || c.attackedMeRecently() || c.rival();
    }

    public static double score(Candidate c, double awareness, boolean isCurrent) {
        double s = 30.0 * (1.0 - Math.min(1.0, c.distance() / Math.max(1.0, awareness)));
        if (c.attackedMeRecently()) s += 100;
        if (c.rival()) s += 25;
        if (c.hostileMob()) s += 10;
        s += (1.0 - c.healthFrac()) * 10.0; // finish weakened enemies
        if (isCurrent) s += 15;             // stickiness avoids flip-flopping
        return s;
    }

    public static Optional<Candidate> select(List<Candidate> all, double awareness, PlayerPolicy policy, UUID current) {
        Candidate best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (Candidate c : all) {
            if (!eligible(c, awareness, policy)) continue;
            double sc = score(c, awareness, c.id().equals(current));
            if (sc > bestScore) { bestScore = sc; best = c; }
        }
        return Optional.ofNullable(best);
    }

    /** Number of eligible enemies, used for "am I outnumbered" decisions. */
    public static int countThreats(List<Candidate> all, double awareness, PlayerPolicy policy) {
        int n = 0;
        for (Candidate c : all) if (eligible(c, awareness, policy)) n++;
        return n;
    }
}
