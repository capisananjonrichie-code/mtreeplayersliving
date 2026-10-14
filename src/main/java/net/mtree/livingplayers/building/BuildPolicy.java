package net.mtree.livingplayers.building;

/**
 * Gate for placing blueprint blocks. Deny-by-default like BlockPolicy: only planks, only into the project's own box,
 * only into EMPTY or replaceable-plant cells (so nothing a player built is ever overwritten), never inside protected
 * regions, never where a claim plugin is installed without a verified bridge.
 */
public final class BuildPolicy {
    private BuildPolicy() {}

    public record Verdict(boolean allowed, String reason) {}

    public static Verdict decide(boolean enabled, boolean inProtectedRegion, String blockingClaimPlugin, String material,
                                 boolean targetReplaceable, boolean insideProjectBox) {
        if (!enabled) return new Verdict(false, "building is disabled");
        if (!insideProjectBox) return new Verdict(false, "outside the project's area");
        if (inProtectedRegion) return new Verdict(false, "inside a protected region");
        if (blockingClaimPlugin != null) return new Verdict(false, "claim plugin '" + blockingClaimPlugin + "' is installed and no verified bridge exists");
        if (!material.endsWith("_PLANKS")) return new Verdict(false, "bots may not build with " + material);
        if (!targetReplaceable) return new Verdict(false, "target cell is occupied by something else");
        return new Verdict(true, "ok");
    }
}
