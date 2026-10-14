package net.mtree.livingplayers.storage;

import java.util.Map;
import java.util.UUID;

/**
 * Persisted construction project. {@code progressHex} flags which blueprint placements (in build order) are done;
 * {@code materials} maps a group ("PLANKS") to the concrete block chosen for this project ("OAK_PLANKS").
 * status: PLANNED, BUILDING, DONE, ABANDONED.
 */
public record ProjectRecord(UUID id, UUID botId, String blueprint, UUID worldUuid, int ox, int oy, int oz, int rotation,
                            Map<String, String> materials, String progressHex, String status, int skipped,
                            long createdAt, long updatedAt) {
    public boolean active() { return status.equals("PLANNED") || status.equals("BUILDING"); }
}
