package net.mtree.livingplayers.protect;

import java.util.Set;

/**
 * THE safety gate for bot block interaction. Pure and deny-by-default: anything not explicitly allowed is refused.
 * Bots are not Players, so claim plugins cannot be asked; therefore the policy is conservative by construction:
 * only natural resource blocks (or blocks the bot itself placed), never near anything that looks built, never inside
 * protected regions, and never at all where a claim plugin is installed without a verified bridge.
 */
public final class BlockPolicy {
    private BlockPolicy() {}

    public enum Op { BREAK, PLACE }

    public record Verdict(boolean allowed, String reason) {
        static Verdict ok() { return new Verdict(true, "ok"); }
        static Verdict deny(String why) { return new Verdict(false, why); }
    }

    /** Facts gathered by the Bukkit layer. */
    public record Context(boolean enabled, boolean inProtectedRegion, String blockingClaimPlugin,
                          boolean artificialNearby, boolean naturalLeavesNearby, boolean placedByBotHere) {}

    private static final Set<String> NATURAL = Set.of("STONE", "GRANITE", "DIORITE", "ANDESITE", "COAL_ORE", "DEEPSLATE_COAL_ORE",
        "DIRT", "GRASS_BLOCK", "SAND", "GRAVEL");

    private static final Set<String> BOT_MAY_PLACE = Set.of("CRAFTING_TABLE");

    public static boolean isNaturalLog(String m) { return m.endsWith("_LOG") && !m.startsWith("STRIPPED_"); }

    public static Verdict decide(Op op, String material, Context c) {
        if (!c.enabled()) return Verdict.deny("block interaction is disabled in survival.yml");
        if (c.inProtectedRegion()) return Verdict.deny("inside a protected region");
        if (c.blockingClaimPlugin() != null)
            return Verdict.deny("claim plugin '" + c.blockingClaimPlugin() + "' is installed and no verified bridge exists");

        if (op == Op.PLACE) {
            return BOT_MAY_PLACE.contains(material) ? Verdict.ok() : Verdict.deny("bots may not place " + material);
        }
        if (c.placedByBotHere() && BOT_MAY_PLACE.contains(material)) return Verdict.ok(); // picking up its own work
        if (c.placedByBotHere()) return Verdict.deny("bot-placed block of an unsupported type");
        if (isNaturalLog(material)) {
            if (!c.naturalLeavesNearby()) return Verdict.deny("log is not part of a natural tree");
        } else if (!NATURAL.contains(material)) {
            return Verdict.deny(material + " is not a natural resource block bots may break");
        }
        if (c.artificialNearby()) return Verdict.deny("player-made structure nearby");
        return Verdict.ok();
    }
}
