package net.mtree.livingplayers.bot;

import java.util.logging.Logger;

/** BotIdentity <-> BotRecord. toRecord runs on the main thread (it serialises ItemStacks). */
public final class BotMapper {
    private BotMapper() {}

    public static BotRecord toRecord(BotIdentity b, long now) {
        return new BotRecord(b.id(), b.name(), b.skinSpec(), b.state(), b.lastLocation(), b.health(), b.hunger(),
            b.saturation(), b.experience(), b.personality(), b.skill(), b.style(), b.goals(), b.home(), b.factionId(),
            b.inventory().serialize(), java.util.Map.copyOf(b.extra()), b.deathCount(), b.createdAt(), now,
            BotRecord.CURRENT_RECORD_VERSION);
    }

    /**
     * A bot saved as ACTIVE had a live entity when the server stopped. Entities are never persisted in chunks,
     * so after a restart it is UNLOADED until its chunk is loaded again - never "ACTIVE without an entity".
     */
    public static BotIdentity fromRecord(BotRecord r, Logger log) {
        LifecycleState st = r.state() == LifecycleState.ACTIVE ? LifecycleState.UNLOADED : r.state();
        return new BotIdentity(r.id(), r.name(), r.skinSpec(), st, r.lastLocation(), r.health(), r.hunger(),
            r.saturation(), r.experience(), r.personality(), r.skill(), r.style(), r.goals(), r.home(), r.factionId(),
            BotInventory.deserialize(r.inventory(), log, r.name()), r.extra(), r.deathCount(), r.createdAt(), r.lastSavedAt());
    }
}
