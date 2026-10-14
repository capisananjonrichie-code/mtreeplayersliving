package net.mtree.livingplayers.director;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Cooldowns that stop the same bot appearing to the same player over and over. Main-thread only. */
public final class EncounterTracker {
    private final Map<String, Long> pairLast = new HashMap<>();
    private final Map<UUID, Long> botLast = new HashMap<>();

    private static String key(UUID player, UUID bot) { return player + "|" + bot; }

    public boolean pairAllowed(UUID player, UUID bot, long nowMs, long cooldownMs) {
        Long last = pairLast.get(key(player, bot));
        return last == null || nowMs - last >= cooldownMs;
    }

    public boolean botAllowed(UUID bot, long nowMs, long cooldownMs) {
        Long last = botLast.get(bot);
        return last == null || nowMs - last >= cooldownMs;
    }

    public void record(UUID player, UUID bot, long nowMs) {
        pairLast.put(key(player, bot), nowMs);
        botLast.put(bot, nowMs);
    }

    public void forget(UUID bot) {
        botLast.remove(bot);
        pairLast.keySet().removeIf(k -> k.endsWith("|" + bot));
    }

    public void prune(long nowMs, long maxAgeMs) {
        pairLast.values().removeIf(t -> nowMs - t > maxAgeMs);
        botLast.values().removeIf(t -> nowMs - t > maxAgeMs);
    }

    public int size() { return pairLast.size(); }
}
