package net.mtree.livingplayers.director;

@FunctionalInterface
public interface TerrainProbe {
    BlockClass classify(int x, int y, int z);
}
