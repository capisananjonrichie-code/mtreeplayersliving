package net.mtree.livingplayers.storage;

import java.util.List;
import java.util.UUID;

/** Immutable, Bukkit-free snapshot of everything social, written/read as one atomic unit. */
public record SocialData(List<Rel> relationships, List<Mem> memories, List<RumorRow> rumors, List<FactionRow> factions) {
    public record Rel(UUID bot, UUID other, boolean otherIsPlayer, double affinity, double trust, double familiarity, long lastAt) {}
    public record Mem(UUID bot, String kind, UUID subject, String text, int importance, long at) {}
    public record RumorRow(UUID bot, UUID rumorId, String kind, String subject, String where, int confidence, int hops, long createdAt) {}
    public record FactionRow(UUID id, String name, UUID founder, long createdAt, List<UUID> members) {}

    public static SocialData empty() { return new SocialData(List.of(), List.of(), List.of(), List.of()); }
}
