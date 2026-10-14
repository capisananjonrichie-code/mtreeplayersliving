package org.bukkit.entity;
public interface Player extends LivingEntity, org.bukkit.command.CommandSender { org.bukkit.GameMode getGameMode(); org.bukkit.inventory.PlayerInventory getInventory(); String getName(); boolean isOnline(); }
