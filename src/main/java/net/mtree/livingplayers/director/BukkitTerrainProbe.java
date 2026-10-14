package net.mtree.livingplayers.director;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Reads blocks ONLY from already-loaded chunks (never loads or generates anything). Main thread only. */
public final class BukkitTerrainProbe implements TerrainProbe {
    private final World world;
    private final boolean strictPassable;

    public BukkitTerrainProbe(World world) { this(world, false); }

    /** strictPassable: any passable non-air block that is not a wild replaceable plant (crops, torches, signs...) counts as ARTIFICIAL. */
    public BukkitTerrainProbe(World world, boolean strictPassable) { this.world = world; this.strictPassable = strictPassable; }

    @Override
    public BlockClass classify(int x, int y, int z) {
        if (y >= world.getMaxHeight()) return BlockClass.AIR;
        if (y < world.getMinHeight()) return BlockClass.UNKNOWN;
        if (!world.isChunkLoaded(x >> 4, z >> 4)) return BlockClass.UNKNOWN;
        Block b = world.getBlockAt(x, y, z);
        Material m = b.getType();
        String n = m.name();
        if (m.isAir()) return BlockClass.AIR;
        if (m == Material.LAVA) return BlockClass.LAVA;
        if (m == Material.WATER || BlockNames.isWaterPlant(n)) return BlockClass.WATER;
        if (b.isLiquid()) return BlockClass.HAZARD;
        if (BlockNames.isHazard(n)) return BlockClass.HAZARD;
        if (BlockNames.isArtificial(n)) return BlockClass.ARTIFICIAL;
        if (b.isPassable()) return strictPassable && !BlockNames.isReplaceablePlant(n) ? BlockClass.ARTIFICIAL : BlockClass.PASSABLE;
        return BlockClass.SOLID;
    }
}
