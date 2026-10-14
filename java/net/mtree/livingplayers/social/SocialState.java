package net.mtree.livingplayers.social;

import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.storage.SocialData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything bots remember socially, in memory (the database copy is written asynchronously from snapshots).
 * "Others" are identified by UUID: real players and other bots alike. Main thread only.
 */
public final class SocialState {
    public static final class BotSocial {
        public final Map<UUID, Relationship> relationships = new HashMap<>();
        public final Map<UUID, Boolean> otherIsPlayer = new HashMap<>();
        public final MemoryStore memory;
        public final RumorBook rumors;
        BotSocial(int memoryMax, int rumorMax) { memory = new MemoryStore(memoryMax); rumors = new RumorBook(rumorMax); }
    }

    private final int memoryMax, rumorMax;
    private final Map<UUID, BotSocial> bots = new HashMap<>();
    public final FactionRegistry factions;
    private boolean dirty;

    public SocialState(int memoryMax, int rumorMax, int factionMax) {
        this.memoryMax = memoryMax; this.rumorMax = rumorMax; this.factions = new FactionRegistry(factionMax);
    }

    public BotSocial of(UUID bot) { return bots.computeIfAbsent(bot, k -> new BotSocial(memoryMax, rumorMax)); }

    public Map<UUID, BotSocial> all() { return bots; }

    public Relationship relationship(UUID bot, UUID other) { return of(bot).relationships.getOrDefault(other, Relationship.NONE); }

    public Relationship record(UUID bot, UUID other, boolean otherIsPlayer, SocialEvent e, Personality p, long now) {
        BotSocial s = of(bot);
        Relationship n = RelationshipModel.apply(s.relationships.getOrDefault(other, Relationship.NONE), e, p, now);
        s.relationships.put(other, n);
        s.otherIsPlayer.put(other, otherIsPlayer);
        dirty = true;
        return n;
    }

    public void remember(UUID bot, MemoryStore.Memory m) { of(bot).memory.add(m); dirty = true; }
    public void hear(UUID bot, Rumor r) { of(bot).rumors.add(r); dirty = true; }

    public void decayAll(long now, long halfLifeMs) {
        for (BotSocial s : bots.values()) s.relationships.replaceAll((k, v) -> RelationshipModel.decay(v, now, halfLifeMs));
    }

    public void forget(UUID bot) {
        bots.remove(bot);
        factions.forget(bot);
        for (BotSocial s : bots.values()) { s.relationships.remove(bot); s.otherIsPlayer.remove(bot); }
        dirty = true;
    }

    /** Immutable copy for the storage thread. */
    public SocialData snapshot() {
        List<SocialData.Rel> rels = new ArrayList<>(); List<SocialData.Mem> mems = new ArrayList<>(); List<SocialData.RumorRow> rumors = new ArrayList<>();
        for (Map.Entry<UUID, BotSocial> e : bots.entrySet()) {
            UUID bot = e.getKey();
            BotSocial s = e.getValue();
            s.relationships.forEach((other, r) -> rels.add(new SocialData.Rel(bot, other, s.otherIsPlayer.getOrDefault(other, false), r.affinity(), r.trust(), r.familiarity(), r.lastAt())));
            for (MemoryStore.Memory m : s.memory.all()) mems.add(new SocialData.Mem(bot, m.kind(), m.subject(), m.text(), m.importance(), m.at()));
            for (Rumor r : s.rumors.all()) rumors.add(new SocialData.RumorRow(bot, r.id(), r.kind(), r.subject(), r.where(), r.confidence(), r.hops(), r.createdAt()));
        }
        List<SocialData.FactionRow> fs = new ArrayList<>();
        for (FactionRegistry.Faction f : factions.all()) fs.add(new SocialData.FactionRow(f.id(), f.name(), f.founder(), f.createdAt(), new ArrayList<>(f.members())));
        return new SocialData(rels, mems, rumors, fs);
    }

    public void load(SocialData d, java.util.Set<UUID> knownBots) {
        bots.clear();
        for (SocialData.Rel r : d.relationships()) if (knownBots.contains(r.bot())) {
            BotSocial s = of(r.bot());
            s.relationships.put(r.other(), new Relationship(r.affinity(), r.trust(), r.familiarity(), r.lastAt()));
            s.otherIsPlayer.put(r.other(), r.otherIsPlayer());
        }
        Map<UUID, List<MemoryStore.Memory>> mem = new HashMap<>();
        for (SocialData.Mem m : d.memories()) if (knownBots.contains(m.bot())) mem.computeIfAbsent(m.bot(), k -> new ArrayList<>()).add(new MemoryStore.Memory(m.kind(), m.subject(), m.text(), m.importance(), m.at()));
        mem.forEach((bot, list) -> of(bot).memory.load(list));
        Map<UUID, List<Rumor>> rum = new HashMap<>();
        for (SocialData.RumorRow r : d.rumors()) if (knownBots.contains(r.bot())) rum.computeIfAbsent(r.bot(), k -> new ArrayList<>()).add(new Rumor(r.rumorId(), r.kind(), r.subject(), r.where(), r.confidence(), r.hops(), r.createdAt()));
        rum.forEach((bot, list) -> of(bot).rumors.load(list));
        List<FactionRegistry.Faction> fs = new ArrayList<>();
        for (SocialData.FactionRow f : d.factions()) {
            java.util.Set<UUID> members = new java.util.LinkedHashSet<>();
            for (UUID m : f.members()) if (knownBots.contains(m)) members.add(m);
            if (!members.isEmpty()) fs.add(new FactionRegistry.Faction(f.id(), f.name(), members.contains(f.founder()) ? f.founder() : members.iterator().next(), members, f.createdAt()));
        }
        factions.load(fs);
        dirty = false;
    }

    public boolean takeDirty() { boolean d = dirty; dirty = false; return d; }
    public void markDirty() { dirty = true; }
}
