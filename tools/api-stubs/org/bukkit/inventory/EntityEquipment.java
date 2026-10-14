package org.bukkit.inventory;
public interface EntityEquipment { void setBoots(ItemStack i); void setLeggings(ItemStack i); void setChestplate(ItemStack i); void setHelmet(ItemStack i);
 ItemStack getBoots(); ItemStack getLeggings(); ItemStack getChestplate(); ItemStack getHelmet(); void setItemInMainHand(ItemStack i); void setItemInOffHand(ItemStack i);
 ItemStack getItemInMainHand(); ItemStack getItemInOffHand(); void setDropChance(EquipmentSlot s, float c); }
