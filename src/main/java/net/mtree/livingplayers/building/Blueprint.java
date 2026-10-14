package net.mtree.livingplayers.building;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A structure plan: block placements relative to an origin (x east, y up, z south), each naming a material GROUP
 * (Stage 5 supports "PLANKS"; the bot picks the actual wood type from what it owns). Pure.
 */
public record Blueprint(String name, String description, int width, int height, int depth, List<Placement> placements) {
    public record Placement(int x, int y, int z, String group) {}

    public Map<String, Integer> materialCounts() {
        Map<String, Integer> m = new TreeMap<>();
        for (Placement p : placements) m.merge(p.group(), 1, Integer::sum);
        return m;
    }

    /** Rotates the plan clockwise by 90 degrees * quarterTurns around its own footprint (door side changes, counts do not). */
    public Blueprint rotated(int quarterTurns) {
        int q = ((quarterTurns % 4) + 4) % 4;
        int w = width, d = depth;
        List<Placement> out = new ArrayList<>(placements.size());
        for (Placement p : placements) {
            int x = p.x(), z = p.z(), cw = w, cd = d;
            for (int i = 0; i < q; i++) {
                int nx = cd - 1 - z, nz = x; // (x,z) -> (d-1-z, x), footprint dims swap
                x = nx; z = nz;
                int t = cw; cw = cd; cd = t;
            }
            out.add(new Placement(x, p.y(), z, p.group()));
        }
        int nw = (q % 2 == 0) ? w : d, nd = (q % 2 == 0) ? d : w;
        return new Blueprint(name, description, nw, height, nd, List.copyOf(out));
    }

    /** Stable build order: bottom layer first, then row by row, so every block has a neighbour or support placed before it. */
    public List<Placement> buildOrder() {
        List<Placement> o = new ArrayList<>(placements);
        o.sort((a, b) -> a.y() != b.y() ? Integer.compare(a.y(), b.y()) : a.z() != b.z() ? Integer.compare(a.z(), b.z()) : Integer.compare(a.x(), b.x()));
        return o;
    }
}
