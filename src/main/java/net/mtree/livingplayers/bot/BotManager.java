package net.mtree.livingplayers.bot;

import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.storage.BotStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry and orchestration for bot identities. MAIN THREAD ONLY: it touches Bukkit entities and worlds.
 * Database work is delegated to {@link BotStorage} using immutable {@link BotRecord} snapshots.
 */
public final class BotManager {
    public record Result(boolean ok, String message) {
        public static Result ok(String m) { return new Result(true, m); }
        public static Result fail(String m) { return new Result(false, m); }
    }

    private final Plugin plugin;
    private final Logger log;
    private final Supplier<Settings> settings;
    private final BotStorage storage;
    private final BotEntityAdapter adapter;
    private final Random rnd = new Random();
    private final Map<UUID, BotIdentity> byId = new LinkedHashMap<>();
    private final Map<String, UUID> byName = new LinkedHashMap<>();
    private final List<Consumer<UUID>> removalListeners = new ArrayList<>();
    private boolean ready;

    public BotManager(Plugin plugin, Supplier<Settings> settings, BotStorage storage, BotEntityAdapter adapter) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.settings = settings;
        this.storage = storage;
        this.adapter = adapter;
    }

    // ---------------------------------------------------------------- registry

    public void load(List<BotRecord> records) {
        for (BotRecord r : records) {
            BotIdentity b = BotMapper.fromRecord(r, log);
            if (byId.containsKey(b.id())) { log.warning("Duplicate bot id in database ignored: " + b.id()); continue; }
            register(b);
        }
        ready = true;
        log.info("Loaded " + byId.size() + " bot identit" + (byId.size() == 1 ? "y" : "ies") + " from the database.");
    }

    private void register(BotIdentity b) {
        byId.put(b.id(), b);
        byName.put(b.name().toLowerCase(Locale.ROOT), b.id());
    }

    public boolean isReady() { return ready; }
    public Collection<BotIdentity> all() { return byId.values(); }
    public int count() { return byId.size(); }
    public void addRemovalListener(Consumer<UUID> l) { removalListeners.add(l); }

    public Optional<BotIdentity> find(String nameOrUuid) {
        if (nameOrUuid == null) return Optional.empty();
        UUID id = byName.get(nameOrUuid.toLowerCase(Locale.ROOT));
        if (id == null) {
            try { id = UUID.fromString(nameOrUuid); } catch (IllegalArgumentException e) { return Optional.empty(); }
        }
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<BotIdentity> byId(UUID id) { return Optional.ofNullable(byId.get(id)); }

    public int activeCount() {
        int n = 0;
        for (BotIdentity b : byId.values()) if (b.state() == LifecycleState.ACTIVE) n++;
        return n;
    }

    public int activeInWorld(World w) {
        int n = 0;
        for (BotIdentity b : byId.values()) {
            if (b.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> e = adapter.entity(b.id());
            if (e.isPresent() && e.get().getWorld().equals(w)) n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- locations

    public Location toLocation(BotLocation l) {
        if (l == null) return null;
        World w = l.worldUuid() != null ? Bukkit.getWorld(l.worldUuid()) : null;
        if (w == null) w = Bukkit.getWorld(l.worldName());
        return w == null ? null : new Location(w, l.x(), l.y(), l.z(), l.yaw(), l.pitch());
    }

    public static BotLocation fromLocation(Location l) {
        return l.getWorld() == null ? null
            : new BotLocation(l.getWorld().getUID(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    // ---------------------------------------------------------------- names

    public boolean isNameTaken(String name) {
        if (byName.containsKey(name.toLowerCase(Locale.ROOT))) return true;
        return Bukkit.getPlayerExact(name) != null || Bukkit.getOfflinePlayerIfCached(name) != null;
    }

    private String generateName(Settings s) {
        Set<String> taken = new HashSet<>(byName.keySet());
        for (Player p : Bukkit.getOnlinePlayers()) taken.add(p.getName());
        for (int i = 0; i < 25; i++) {
            String n = NameRules.generate(rnd, s.bots().namePool(), taken);
            if (Bukkit.getOfflinePlayerIfCached(n) == null) return n;
            taken.add(n); // a real player already uses it
        }
        throw new IllegalStateException("could not find a bot name that no real player uses");
    }

    // ---------------------------------------------------------------- create / activate / deactivate / remove

    /** Creates and persists a new identity in state INACTIVE. {@code desiredName} may be null for a generated name. */
    public BotIdentity createBot(String desiredName, Location at) {
        Settings s = settings.get();
        String name = desiredName != null ? desiredName : generateName(s);
        Personality p = PersonalityGenerator.generate(rnd, s.personalities());
        String skin = "default";
        if (s.bots().skinMode().equals("pool") && !s.bots().skinPool().isEmpty())
            skin = SkinSpec.parse(s.bots().skinPool().get(rnd.nextInt(s.bots().skinPool().size()))).serialize();
        List<String> goals = PersonalityGenerator.pickGoals(p, rnd, s.goalsPerBot()).stream().map(Enum::name).toList();
        long now = System.currentTimeMillis();
        BotIdentity b = new BotIdentity(UUID.randomUUID(), name, skin, LifecycleState.INACTIVE,
            at == null ? null : fromLocation(at), s.bots().startingHealth(), s.bots().startingHunger(), 5f, 0, p,
            PersonalityGenerator.pickSkill(rnd, s.skillWeights()), PersonalityGenerator.pickStyle(p, rnd), goals,
            null, null, new BotInventory(), Map.of(), 0, now, now);
        register(b);
        b.markDirty();
        saveNow(b);
        log.info("Created bot " + name + " (" + b.id() + ") skill=" + b.skill() + " style=" + b.style());
        return b;
    }

    /** Spawns the bot's entity. Never loads chunks; refuses if the target chunk is not already loaded. */
    public Result activate(BotIdentity bot, Location at) {
        if (!ready) return Result.fail("still starting up");
        switch (bot.state()) {
            case ACTIVE -> { return Result.fail(bot.name() + " is already active"); }
            case DISABLED -> { return Result.fail(bot.name() + " is disabled (use /mtreebots enable)"); }
            case DEAD -> { return Result.fail(bot.name() + " is dead and respawn is disabled"); }
            default -> { /* INACTIVE, UNLOADED, RESPAWNING may spawn */ }
        }
        if (activeCount() >= settings.get().lifecycle().maxActiveBots()) return Result.fail("max-active-bots reached");
        Location loc = at != null ? at : toLocation(bot.lastLocation());
        if (loc == null || loc.getWorld() == null) return Result.fail("world for " + bot.name() + " is not loaded");
        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4))
            return Result.fail("target chunk is not loaded (chunks are never force-loaded)");

        BotEntityAdapter.SpawnOutcome out = adapter.spawn(bot, loc);
        if (!out.success()) return Result.fail("spawn failed: " + out.failure());
        if (!bot.setState(LifecycleState.ACTIVE)) {
            adapter.despawn(bot.id());
            return Result.fail("illegal state transition " + bot.state() + " -> ACTIVE");
        }
        bot.putExtra(BotIdentity.EXTRA_LAST_SPAWN_AT, String.valueOf(System.currentTimeMillis()));
        bot.setLastLocation(fromLocation(loc));
        bot.setOutOfRangeSinceMs(-1);
        bot.setWorldMissingWarned(false);
        saveNow(bot);
        return Result.ok(bot.name() + " spawned");
    }

    /** Despawns the entity (if any) and moves the identity to INACTIVE/UNLOADED/DISABLED. Persistence is untouched. */
    public Result deactivate(BotIdentity bot, LifecycleState target) {
        if (target == LifecycleState.ACTIVE) return Result.fail("use activate()");
        if (!bot.state().canTransitionTo(target)) return Result.fail("cannot go " + bot.state() + " -> " + target);
        if (bot.state() == LifecycleState.ACTIVE) {
            adapter.pullState(bot);
            adapter.despawn(bot.id());
        }
        bot.setState(target);
        saveNow(bot);
        return Result.ok(bot.name() + " is now " + target);
    }

    /** The entity disappeared without a death event (chunk unload handled elsewhere; cleanup plugins, /kill-less removal). */
    public void onEntityLost(BotIdentity bot) {
        adapter.forget(bot.id());
        boolean repeating = bot.recordUnexpectedLoss(System.currentTimeMillis());
        LifecycleState next = repeating ? LifecycleState.INACTIVE : LifecycleState.UNLOADED;
        if (repeating) log.warning("Entity of bot " + bot.name() + " keeps disappearing (another plugin removing it?); parking it as INACTIVE.");
        else Debug.log("Entity of bot " + bot.name() + " vanished; marking UNLOADED.");
        bot.setState(next);
        saveNow(bot);
    }

    /** Called from EntitiesUnloadEvent: the chunk is going away; the (non-persistent) entity will not be saved. */
    public void onEntityUnloading(UUID botId) {
        BotIdentity bot = byId.get(botId);
        if (bot == null || bot.state() != LifecycleState.ACTIVE) return;
        adapter.pullState(bot);
        adapter.forget(botId);
        bot.setState(LifecycleState.UNLOADED);
        saveNow(bot);
    }

    public void handleDeath(BotIdentity bot, LivingEntity entity, EntityDeathEvent event) {
        event.getDrops().clear();
        event.setDroppedExp(0);
        if (bot.state() != LifecycleState.ACTIVE) return; // already handled
        Settings s = settings.get();
        adapter.pullState(bot); // equipment is still on the dying entity
        Location loc = entity.getLocation();
        if (s.death().dropInventory() && loc.getWorld() != null)
            for (ItemStack it : bot.inventory().drainAll()) loc.getWorld().dropItemNaturally(loc, it);
        adapter.forget(bot.id());
        bot.incrementDeaths();
        bot.setHealth(s.bots().startingHealth());
        bot.setHunger(s.bots().startingHunger());
        if (!s.death().keepExperience()) bot.setExperience(0);
        long now = System.currentTimeMillis();
        bot.putExtra(BotIdentity.EXTRA_LAST_DEATH_AT, String.valueOf(now));
        if (s.death().respawnEnabled()) {
            bot.putExtra(BotIdentity.EXTRA_RESPAWN_AT, String.valueOf(now + s.death().respawnDelaySeconds() * 1000L));
            bot.setState(LifecycleState.RESPAWNING);
        } else {
            bot.setState(LifecycleState.DEAD);
        }
        if (hasWorld(loc)) bot.setLastLocation(fromLocation(loc));
        saveNow(bot);
        log.info("Bot " + bot.name() + " died (deaths=" + bot.deathCount() + ") -> " + bot.state());
    }

    private static boolean hasWorld(Location l) { return l.getWorld() != null; }

    /** Permanent deletion. Never silently destroys items: either drops them or refuses. */
    public Result remove(BotIdentity bot) {
        boolean wantDrop = settings.get().bots().dropInventoryOnRemove();
        Location where = null;
        if (bot.state() == LifecycleState.ACTIVE) {
            adapter.pullState(bot);
            where = adapter.entity(bot.id()).map(LivingEntity::getLocation).orElse(null);
        }
        if (where == null) where = toLocation(bot.lastLocation());
        if (!bot.inventory().isEmpty() && wantDrop) {
            if (where == null || where.getWorld() == null || !where.getWorld().isChunkLoaded(where.getBlockX() >> 4, where.getBlockZ() >> 4))
                return Result.fail(bot.name() + " carries items but its location is not loaded, so they cannot be dropped. "
                    + "Go there first, or set bots.yml removal.drop-inventory-on-remove: false to discard them.");
            for (ItemStack it : bot.inventory().drainAll()) where.getWorld().dropItemNaturally(where, it);
        }
        adapter.despawn(bot.id());
        byId.remove(bot.id());
        byName.remove(bot.name().toLowerCase(Locale.ROOT));
        removalListeners.forEach(l -> l.accept(bot.id()));
        storage.delete(bot.id()).whenComplete((deleted, err) -> {
            if (err != null) log.log(Level.SEVERE, "Failed to delete bot " + bot.name() + " from the database", err);
        });
        log.info("Removed bot " + bot.name() + " (" + bot.id() + ")");
        return Result.ok(bot.name() + " removed");
    }

    // ---------------------------------------------------------------- persistence

    private void saveNow(BotIdentity bot) {
        long now = System.currentTimeMillis();
        BotRecord rec = BotMapper.toRecord(bot, now);
        bot.markClean(now);
        storage.save(rec).whenComplete((v, err) -> {
            if (err != null) { log.log(Level.SEVERE, "Failed to save bot " + bot.name(), err); redirty(List.of(bot)); }
        });
    }

    /** Periodic save: ACTIVE bots always (position/health change), others only when dirty. One DB transaction. */
    public CompletableFuture<Void> saveAll(boolean dirtyOnly) {
        long now = System.currentTimeMillis();
        List<BotRecord> recs = new ArrayList<>();
        List<BotIdentity> saved = new ArrayList<>();
        for (BotIdentity b : byId.values()) {
            if (b.state() == LifecycleState.ACTIVE) { adapter.pullState(b); b.markDirty(); }
            if (dirtyOnly && !b.dirty()) continue;
            recs.add(BotMapper.toRecord(b, now));
            b.markClean(now);
            saved.add(b);
        }
        if (recs.isEmpty()) return CompletableFuture.completedFuture(null);
        return storage.saveAll(recs).whenComplete((v, err) -> {
            if (err != null) { log.log(Level.SEVERE, "Periodic bot save failed; will retry", err); redirty(saved); }
        });
    }

    private void redirty(List<BotIdentity> bots) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> bots.forEach(BotIdentity::markDirty));
    }

    /** onDisable: snapshot EVERY bot (ACTIVE bots keep state ACTIVE so they return after restart), remove entities, block until written. */
    public void shutdown() {
        if (!ready) return;
        long now = System.currentTimeMillis();
        List<BotRecord> recs = new ArrayList<>();
        for (BotIdentity b : byId.values()) {
            if (b.state() == LifecycleState.ACTIVE) adapter.pullState(b);
            recs.add(BotMapper.toRecord(b, now));
        }
        for (UUID id : adapter.trackedBotIds()) adapter.despawn(id);
        try {
            storage.saveAll(recs).get(20, TimeUnit.SECONDS);
            log.info("Saved " + recs.size() + " bot(s) and removed their entities.");
        } catch (Exception e) {
            log.log(Level.SEVERE, "FINAL BOT SAVE FAILED - recent changes may be lost", e);
        }
        ready = false;
    }
}
