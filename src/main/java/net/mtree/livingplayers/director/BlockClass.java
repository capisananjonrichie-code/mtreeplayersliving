package net.mtree.livingplayers.director;

/** Coarse block categories used by Bukkit-free spawn-safety logic. */
public enum BlockClass {
    /** Empty air. */
    AIR,
    /** Non-solid, walk-through, harmless (grass, flowers, snow layer...). */
    PASSABLE,
    /** Natural solid ground. */
    SOLID,
    /** Solid but human-made (planks, bricks, glass, doors, chests, beds ...). */
    ARTIFICIAL,
    WATER,
    LAVA,
    /** Fire, cactus, magma, berry bush, powder snow, dripstone, ... */
    HAZARD,
    /** Chunk not loaded / outside world: never treated as safe. */
    UNKNOWN
}
