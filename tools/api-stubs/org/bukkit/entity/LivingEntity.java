package org.bukkit.entity;
public interface LivingEntity extends Entity { double getHealth(); void setHealth(double h); @Deprecated double getMaxHealth(); org.bukkit.inventory.EntityEquipment getEquipment();
 void setCanPickupItems(boolean b); void setRemoveWhenFarAway(boolean b); boolean hasLineOfSight(org.bukkit.Location l); boolean hasLineOfSight(Entity e); void swingMainHand(); Player getKiller(); org.bukkit.Location getEyeLocation(); void damage(double a); void damage(double a, Entity src); }
