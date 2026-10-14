package net.mtree.livingplayers.nav;

import java.util.ArrayDeque;

/** Flags a mover that is trying to travel but is not making progress. Call {@link #sample} at a fixed interval. */
public final class StuckDetector {
    private record Pos(double x, double y, double z) {}

    private final int window;
    private final double minMove;
    private final ArrayDeque<Pos> history = new ArrayDeque<>();

    /** @param window number of samples to look back; @param minMove blocks it must have moved over that window */
    public StuckDetector(int window, double minMove) { this.window = Math.max(2, window); this.minMove = minMove; }

    public boolean sample(double x, double y, double z) {
        history.addLast(new Pos(x, y, z));
        if (history.size() > window) history.removeFirst();
        if (history.size() < window) return false;
        Pos old = history.peekFirst();
        double dx = x - old.x(), dy = y - old.y(), dz = z - old.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < minMove;
    }

    public void reset() { history.clear(); }
}
