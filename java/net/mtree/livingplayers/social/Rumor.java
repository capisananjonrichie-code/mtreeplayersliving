package net.mtree.livingplayers.social;

import java.util.UUID;

/**
 * Hearsay. {@code where} is a COARSE phrase ("a good walk away to the north-east"), never coordinates, so sharing a rumor
 * cannot leak a secret base location. Confidence drops with every retelling.
 */
public record Rumor(UUID id, String kind, String subject, String where, int confidence, int hops, long createdAt) {}
