package net.mtree.livingplayers.storage;

import java.util.UUID;

/** A block a bot placed itself. Bots may later pick these up; they are never confused with player builds. */
public record PlacedBlock(UUID worldUuid, int x, int y, int z, UUID botId, String material, long placedAt) {}
