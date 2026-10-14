package net.mtree.livingplayers.chat;

import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.social.Relationship;
import net.mtree.livingplayers.social.Stance;

/** Will this bot do what it was asked? Depends on how it feels about the asker, its temperament, and whether it is busy. Pure. */
public final class Compliance {
    private Compliance() {}

    public enum Response { ACCEPT, REFUSE_BUSY, REFUSE_DISLIKE, NOT_SUPPORTED }

    public static Response decide(Intent intent, Relationship rel, Personality p, boolean busyFighting) {
        switch (intent) {
            case TRADE_REQUEST: return Response.NOT_SUPPORTED; // honest: trading is not implemented yet
            case FOLLOW_REQUEST:
            case HELP_REQUEST:
                if (busyFighting) return Response.REFUSE_BUSY;
                Stance s = rel.stance();
                if (s == Stance.HOSTILE || s == Stance.RIVAL) return Response.REFUSE_DISLIKE;
                double score = rel.affinity() + 25 * p.get(Trait.COOPERATION) + 15 * p.get(Trait.FRIENDLINESS) - 10 * p.get(Trait.AGGRESSION);
                return score >= (intent == Intent.FOLLOW_REQUEST ? 10 : 5) ? Response.ACCEPT : Response.REFUSE_DISLIKE;
            default:
                return Response.ACCEPT; // stopping and talking are always fine
        }
    }
}
