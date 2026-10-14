package net.mtree.livingplayers.entity;

import net.mtree.livingplayers.bot.BotIdentity;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * The ONLY seam between bot logic and a concrete world representation. AI/lifecycle code talks to this
 * interface; swapping Mannequin for another representation (NMS fake player, packet NPC) means writing a new
 * implementation, not touching behaviour code. All methods are main-thread only.
 */
public interface BotEntityAdapter {

    record SpawnOutcome(boolean success, LivingEntity entity, String failure) {
        public static SpawnOutcome ok(LivingEntity e) { return new SpawnOutcome(true, e, null); }
        public static SpawnOutcome fail(String why) { return new SpawnOutcome(false, null, why); }
    }

    /** Human-readable representation name, e.g. "mannequin". */
    String representation();

    /** Spawns the render entity. Must refuse if a live entity already exists for this bot (no duplicates). */
    SpawnOutcome spawn(BotIdentity bot, Location at);

    /** Removes the entity if present. Never touches persistence. */
    void despawn(UUID botId);

    /** Forgets the registry entry without removing anything (the entity is already gone/dying). */
    void forget(UUID botId);

    Optional<LivingEntity> entity(UUID botId);

    /** Identifies plugin-owned entities by persistent-data tag - never by name or skin. */
    Optional<UUID> botIdOf(Entity entity);

    Collection<UUID> trackedBotIds();

    /** Copies entity state (location, health, equipment wear) into the identity. */
    void pullState(BotIdentity bot);

    /** Pushes identity state (equipment, health) onto the entity. */
    void pushState(BotIdentity bot);

    /** Removes tagged entities that are not the registered live entity of their bot. Returns how many. */
    int sweepOrphans(Collection<World> worlds);

    /** Same check for one entity (EntitiesLoadEvent). Returns true if it was removed. */
    boolean removeIfOrphan(Entity entity);
}
