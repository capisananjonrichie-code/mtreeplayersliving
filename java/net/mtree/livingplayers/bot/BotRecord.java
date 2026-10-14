package net.mtree.livingplayers.bot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Immutable, thread-safe persistence snapshot of one bot. Built on the main thread from
 * live state, then handed to the storage thread. Contains no Bukkit objects.
 *
 * @param inventory slot key ("s0".."s35", "a0".."a3", "off") -> base64 of ItemStack#serializeAsBytes
 * @param extra     open-ended string map (respawn timers, counters) so later stages need no schema change
 */
public record BotRecord(
    UUID id, String name, String skinSpec, LifecycleState state,
    BotLocation lastLocation, double health, int hunger, float saturation, long experience,
    Personality personality, CombatSkill skill, CombatStyle style, List<String> goals,
    BotLocation home, String factionId,
    Map<String, String> inventory, Map<String, String> extra,
    int deathCount, long createdAt, long lastSavedAt, int recordVersion) {

    public static final int CURRENT_RECORD_VERSION = 1;
}
