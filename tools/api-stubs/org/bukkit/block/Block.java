package org.bukkit.block;
import java.util.Collection; import org.bukkit.*; import org.bukkit.inventory.ItemStack;
public interface Block { Material getType(); boolean isLiquid(); boolean isPassable(); World getWorld(); int getX(); int getY(); int getZ(); Location getLocation();
 Block getRelative(int x,int y,int z); Collection<ItemStack> getDrops(ItemStack tool); void setType(Material m); org.bukkit.block.data.BlockData getBlockData(); }
