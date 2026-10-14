package net.mtree.livingplayers.social;

/** Things that change how a bot feels about someone. Deliberately few: only significant interactions count. */
public enum SocialEvent {
    GREETED(2, 1), CHATTED(1, 0.5), THANKED(3, 1), GIFT(8, 4), HELPED(10, 8), SAVED(20, 15),
    SHARED_TIME(2.5, 1.5), BICKERED(-3, -1), INSULTED(-8, -3), ATTACKED(-25, -15), KILLED_FRIEND(-40, -30), KILLED_ME(-60, -40);

    public final double affinity, trust;
    SocialEvent(double a, double t) { affinity = a; trust = t; }
}
