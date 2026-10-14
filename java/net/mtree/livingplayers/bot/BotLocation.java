package net.mtree.livingplayers.bot;

import java.util.UUID;

/** Bukkit-free location. World UUID is authoritative; the name is a fallback/diagnostic. */
public record BotLocation(UUID worldUuid, String worldName, double x, double y, double z, float yaw, float pitch) {}
