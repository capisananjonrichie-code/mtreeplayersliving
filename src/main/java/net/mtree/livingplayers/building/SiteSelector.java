package net.mtree.livingplayers.building;

import net.mtree.livingplayers.director.BlockClass;
import net.mtree.livingplayers.director.TerrainProbe;

import java.util.ArrayList;
import java.util.List;

/**
 * Chooses where a blueprint may be built: level natural ground, empty air above, nothing hazardous, and NO player-made
 * blocks within a margin (so a bot never builds into or against a player's structure). Unknown (unloaded) = rejected.
 * Pure: it only reads a TerrainProbe and a surface-height function.
 */
public final class SiteSelector {
    private SiteSelector() {}

    @FunctionalInterface
    public interface Surface { /** FEET y of the top walkable surface at (x,z), or Integer.MIN_VALUE if unknown. */ int feetY(int x, int z); }

    public record Site(int x, int y, int z) {}

    public record Check(boolean ok, String reason) {}

    public static Check check(TerrainProbe p, int ox, int oy, int oz, int w, int d, int h, int margin) {
        for (int x = 0; x < w; x++)
            for (int z = 0; z < d; z++) {
                BlockClass ground = p.classify(ox + x, oy - 1, oz + z);
                if (ground == BlockClass.UNKNOWN) return new Check(false, "chunk not loaded");
                if (ground != BlockClass.SOLID) return new Check(false, "ground is not natural solid (" + ground + ")");
                for (int y = 0; y <= h; y++) { // volume plus one block of headroom
                    BlockClass c = p.classify(ox + x, oy + y, oz + z);
                    if (c == BlockClass.UNKNOWN) return new Check(false, "chunk not loaded");
                    if (c != BlockClass.AIR && c != BlockClass.PASSABLE) return new Check(false, "site is obstructed (" + c + ")");
                }
            }
        for (int x = -margin; x < w + margin; x++)
            for (int z = -margin; z < d + margin; z++)
                for (int y = -2; y <= h + 1; y++) {
                    BlockClass c = p.classify(ox + x, oy + y, oz + z);
                    if (c == BlockClass.ARTIFICIAL) return new Check(false, "player-made structure within " + margin + " blocks");
                    if (c == BlockClass.LAVA || c == BlockClass.HAZARD) return new Check(false, "hazard nearby");
                    if (c == BlockClass.UNKNOWN) return new Check(false, "surroundings not loaded");
                }
        return new Check(true, "ok");
    }

    /** Nearest acceptable origin to (cx,cz) within radius, scanning outward in a square spiral-ish order. Null if none. */
    public static Site find(TerrainProbe p, Surface surface, int cx, int cz, int radius, int w, int d, int h, int margin, int maxCandidates) {
        List<int[]> cells = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx += 2)
            for (int dz = -radius; dz <= radius; dz += 2) cells.add(new int[] {dx, dz});
        cells.sort((a, b) -> Integer.compare(a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]));
        int tried = 0;
        for (int[] c : cells) {
            if (tried >= maxCandidates) break;
            int ox = cx + c[0], oz = cz + c[1];
            int y0 = surface.feetY(ox, oz);
            if (y0 == Integer.MIN_VALUE) continue;
            boolean flat = true;
            for (int x = 0; x < w && flat; x++)
                for (int z = 0; z < d && flat; z++) flat = surface.feetY(ox + x, oz + z) == y0;
            if (!flat) continue; // cheap reject before the expensive check
            tried++;
            if (check(p, ox, y0, oz, w, d, h, margin).ok()) return new Site(ox, y0, oz);
        }
        return null;
    }
}
