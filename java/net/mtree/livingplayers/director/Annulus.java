package net.mtree.livingplayers.director;

import java.util.Random;

/** Samples a block offset uniformly (by area) from a ring around a centre. */
public final class Annulus {
    private Annulus() {}

    public record Offset(int dx, int dz) {}

    public static Offset sample(Random rnd, double minDist, double maxDist) {
        double lo = Math.max(0, Math.min(minDist, maxDist)), hi = Math.max(lo, maxDist);
        double r = Math.sqrt(lo * lo + rnd.nextDouble() * (hi * hi - lo * lo));
        double a = rnd.nextDouble() * Math.PI * 2;
        return new Offset((int) Math.round(Math.cos(a) * r), (int) Math.round(Math.sin(a) * r));
    }
}
