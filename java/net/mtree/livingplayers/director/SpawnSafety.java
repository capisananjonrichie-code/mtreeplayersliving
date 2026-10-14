package net.mtree.livingplayers.director;

import static net.mtree.livingplayers.director.BlockClass.*;

/**
 * Pure spawn-safety rules. Coordinates are the FEET block. The caller guarantees nothing about chunks:
 * any UNKNOWN block makes the position unsafe, so this never encourages chunk loading.
 */
public final class SpawnSafety {
    private SpawnSafety() {}

    public record Rules(int minY, int maxY, boolean allowWater, int hazardRadius, int avoidArtificialRadius) {
        public static Rules defaults() { return new Rules(-60, 316, false, 1, 3); }
    }

    public record Result(boolean ok, String reason) {
        static Result pass() { return new Result(true, "ok"); }
        static Result fail(String why) { return new Result(false, why); }
    }

    public static Result check(TerrainProbe p, int x, int y, int z, Rules r) {
        if (y < r.minY() || y > r.maxY()) return Result.fail("outside allowed Y range");

        BlockClass ground = p.classify(x, y - 1, z), feet = p.classify(x, y, z), head = p.classify(x, y + 1, z);
        if (ground == UNKNOWN || feet == UNKNOWN || head == UNKNOWN) return Result.fail("chunk not loaded");

        switch (ground) {
            case SOLID, ARTIFICIAL -> { /* fine so far */ }
            case WATER -> { if (!r.allowWater()) return Result.fail("standing on water"); }
            case LAVA -> { return Result.fail("standing on lava"); }
            case HAZARD -> { return Result.fail("hazardous ground"); }
            case AIR, PASSABLE -> { return Result.fail("no solid ground"); }
            default -> { return Result.fail("unsupported ground"); }
        }
        if (!isBreathable(feet) || !isBreathable(head)) return Result.fail("no headroom / liquid at feet");

        int hr = Math.max(0, r.hazardRadius());
        for (int dx = -hr; dx <= hr; dx++)
            for (int dz = -hr; dz <= hr; dz++)
                for (int dy = -1; dy <= 1; dy++) {
                    BlockClass c = p.classify(x + dx, y + dy, z + dz);
                    if (c == LAVA || c == HAZARD) return Result.fail("hazard nearby");
                    if (c == UNKNOWN) return Result.fail("neighbour chunk not loaded");
                }

        int ar = Math.max(0, r.avoidArtificialRadius());
        if (ar > 0) {
            for (int dx = -ar; dx <= ar; dx++)
                for (int dz = -ar; dz <= ar; dz++)
                    for (int dy = -1; dy <= 2; dy++)
                        if (p.classify(x + dx, y + dy, z + dz) == ARTIFICIAL) return Result.fail("player-made structure nearby");
        } else if (ground == ARTIFICIAL) {
            return Result.fail("artificial ground");
        }

        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : sides) {
            boolean supported = false;
            for (int dy = 1; dy <= 4 && !supported; dy++) {
                BlockClass c = p.classify(x + s[0], y - dy, z + s[1]);
                if (c == LAVA) return Result.fail("lava below edge");
                if (c == UNKNOWN) return Result.fail("neighbour chunk not loaded");
                supported = c == SOLID || c == ARTIFICIAL || c == WATER;
            }
            if (!supported) return Result.fail("cliff edge");
        }
        return Result.pass();
    }

    private static boolean isBreathable(BlockClass c) { return c == AIR || c == PASSABLE; }
}
