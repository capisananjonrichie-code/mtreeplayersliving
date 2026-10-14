package net.mtree.livingplayers.chat;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Anti-spam: per-bot cooldown for ambient talk plus a sliding global cap. Direct replies skip the per-bot cooldown only. Main thread. */
public final class ChatLimiter {
    private final long perBotCooldownMs, windowMs;
    private final int globalMax;
    private final Map<UUID, Long> lastByBot = new HashMap<>();
    private final ArrayDeque<Long> window = new ArrayDeque<>();

    public ChatLimiter(long perBotCooldownMs, int globalMaxPerMinute) {
        this.perBotCooldownMs = perBotCooldownMs; this.globalMax = Math.max(1, globalMaxPerMinute); this.windowMs = 60_000;
    }

    public boolean tryAcquire(UUID bot, long now, boolean directReply) {
        while (!window.isEmpty() && now - window.peekFirst() >= windowMs) window.removeFirst();
        if (window.size() >= globalMax) return false;
        Long last = lastByBot.get(bot);
        if (!directReply && last != null && now - last < perBotCooldownMs) return false;
        window.addLast(now);
        lastByBot.put(bot, now);
        return true;
    }
}
