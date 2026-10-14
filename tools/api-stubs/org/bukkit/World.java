package org.bukkit;
import java.util.*; import java.util.function.Consumer; import org.bukkit.entity.Entity; import org.bukkit.block.Block; import org.bukkit.inventory.ItemStack;
public interface World { enum Environment { NORMAL, NETHER, THE_END, CUSTOM }
 UUID getUID(); String getName(); Environment getEnvironment(); boolean isChunkLoaded(int x,int z); int getHighestBlockYAt(int x,int z,HeightMap h);
 Block getBlockAt(int x,int y,int z); int getMinHeight(); int getMaxHeight(); <T extends Entity> T spawn(Location l, Class<T> c, Consumer<? super T> f);
 <T extends Entity> Collection<T> getEntitiesByClass(Class<T> c); Entity dropItemNaturally(Location l, ItemStack i); WorldBorder getWorldBorder(); long getTime(); boolean hasStorm(); org.bukkit.util.RayTraceResult rayTraceBlocks(Location l, org.bukkit.util.Vector d, double max); java.util.Collection<Entity> getNearbyEntities(Location l,double x,double y,double z); Difficulty getDifficulty(); Block getBlockAt(Location l); }
