package net.mtree.livingplayers.nav;

import net.mtree.livingplayers.director.BlockClass;
import net.mtree.livingplayers.director.TerrainProbe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded A* over walkable block positions. Pure: it only reads a {@link TerrainProbe}.
 *
 * <p>A node (x,y,z) is the FEET block; it is standable when the block below is solid ground and the feet and head
 * blocks are breathable. Moves: flat step, step up (max 1), drop (up to {@code maxDrop}, fall-damage safe), and
 * flat diagonals that do not cut wall corners. Water, lava, hazards and UNKNOWN (unloaded) blocks are never entered,
 * so planning can never pull chunks in or route through danger. Swimming, ladders, doors are NOT handled (yet).
 */
public final class Pathfinder {
    private Pathfinder() {}

    public record Node(int x, int y, int z) {}

    public record Limits(int maxNodes, int maxDistance, int maxDrop) {
        public static Limits defaults() { return new Limits(1500, 48, 3); }
    }

    public enum Status { FOUND, PARTIAL, NO_PATH, START_INVALID, GOAL_INVALID }

    public record PathResult(Status status, List<Node> path, int expanded) {
        public boolean usable() { return (status == Status.FOUND || status == Status.PARTIAL) && !path.isEmpty(); }
    }

    private static boolean breathable(BlockClass c) { return c == BlockClass.AIR || c == BlockClass.PASSABLE; }
    private static boolean ground(BlockClass c) { return c == BlockClass.SOLID || c == BlockClass.ARTIFICIAL; }

    public static boolean standable(TerrainProbe p, int x, int y, int z) {
        return ground(p.classify(x, y - 1, z)) && breathable(p.classify(x, y, z)) && breathable(p.classify(x, y + 1, z));
    }

    private static boolean free(TerrainProbe p, int x, int y, int z) { return breathable(p.classify(x, y, z)); }

    private record Entry(Node node, double f) {}

    public static PathResult find(TerrainProbe p, Node start, Node goal, Limits lim) {
        if (!standable(p, start.x(), start.y(), start.z())) return new PathResult(Status.START_INVALID, List.of(), 0);
        if (!standable(p, goal.x(), goal.y(), goal.z())) return new PathResult(Status.GOAL_INVALID, List.of(), 0);
        if (start.equals(goal)) return new PathResult(Status.FOUND, List.of(start), 0);

        PriorityQueue<Entry> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<Node, Double> g = new HashMap<>();
        Map<Node, Node> parent = new HashMap<>();
        java.util.Set<Node> closed = new java.util.HashSet<>();
        g.put(start, 0.0);
        open.add(new Entry(start, h(start, goal)));
        Node best = start;
        double bestH = h(start, goal);
        int expanded = 0;

        while (!open.isEmpty() && expanded < lim.maxNodes()) {
            Node cur = open.poll().node();
            if (!closed.add(cur)) continue;
            expanded++;
            if (cur.equals(goal)) return new PathResult(Status.FOUND, rebuild(parent, cur), expanded);
            double hc = h(cur, goal);
            if (hc < bestH) { bestH = hc; best = cur; }
            for (Move m : neighbours(p, cur, lim.maxDrop())) {
                Node n = m.node();
                if (closed.contains(n)) continue;
                if (Math.abs(n.x() - start.x()) > lim.maxDistance() || Math.abs(n.z() - start.z()) > lim.maxDistance()) continue;
                double ng = g.get(cur) + m.cost();
                if (ng < g.getOrDefault(n, Double.MAX_VALUE)) {
                    g.put(n, ng);
                    parent.put(n, cur);
                    open.add(new Entry(n, ng + h(n, goal)));
                }
            }
        }
        if (!best.equals(start)) return new PathResult(Status.PARTIAL, rebuild(parent, best), expanded);
        return new PathResult(Status.NO_PATH, List.of(), expanded);
    }

    private static double h(Node a, Node b) {
        double dx = Math.abs(a.x() - b.x()), dz = Math.abs(a.z() - b.z());
        return (dx + dz) + (Math.sqrt(2) - 2) * Math.min(dx, dz) + Math.abs(a.y() - b.y()) * 0.5;
    }

    private static List<Node> rebuild(Map<Node, Node> parent, Node end) {
        List<Node> out = new ArrayList<>();
        for (Node n = end; n != null; n = parent.get(n)) out.add(n);
        Collections.reverse(out);
        return out;
    }

    private record Move(Node node, double cost) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static List<Move> neighbours(TerrainProbe p, Node c, int maxDrop) {
        List<Move> out = new ArrayList<>(12);
        int x = c.x(), y = c.y(), z = c.z();
        for (int[] d : DIRS) {
            int nx = x + d[0], nz = z + d[1];
            if (standable(p, nx, y, nz)) {
                out.add(new Move(new Node(nx, y, nz), 1.0));
            } else if (standable(p, nx, y + 1, nz) && free(p, x, y + 2, z)) {
                out.add(new Move(new Node(nx, y + 1, nz), 1.5)); // one-block step up; needs headroom above current cell
            } else if (free(p, nx, y, nz) && free(p, nx, y + 1, nz)) {
                for (int drop = 1; drop <= maxDrop; drop++) {
                    boolean clear = true;
                    for (int k = y - drop + 2; k <= y + 1 && clear; k++) clear = free(p, nx, k, nz);
                    if (!clear) break;
                    if (standable(p, nx, y - drop, nz)) { out.add(new Move(new Node(nx, y - drop, nz), 1.0 + 0.5 * drop)); break; }
                    if (!free(p, nx, y - drop, nz)) break; // landed on something non-standable (water/lava/hazard): stop
                }
            }
        }
        for (int[] dx : DIRS) for (int[] dz : DIRS) {
            if (dx[0] == 0 || dz[1] == 0) continue; // only combine an x-step with a z-step
            int nx = x + dx[0], nz = z + dz[1];
            boolean sideA = free(p, x + dx[0], y, z) && free(p, x + dx[0], y + 1, z);
            boolean sideB = free(p, x, y, z + dz[1]) && free(p, x, y + 1, z + dz[1]);
            if (sideA && sideB && standable(p, nx, y, nz)) out.add(new Move(new Node(nx, y, nz), 1.414));
        }
        return out;
    }
}
