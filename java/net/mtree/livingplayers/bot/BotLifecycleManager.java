package net.mtree.livingplayers.bot;

import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Drives state transitions on a timer: restores UNLOADED bots when their chunk is loaded and a player is near,
 * respawns dead bots, parks bots that no player is near, and repairs bots whose entity vanished.
 * Work per run is bounded (at most 2 activations) and nothing here loads chunks.
 */
public final class BotLifecycleManager {
    private static final int MAX_ACTIVATIONS_PER_RUN = 2;

    private final Plugin plugin;
    private final Logger log;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final Supplier<Location> respawnLocator;
    private BukkitTask reconcileTask, saveTask;

    public BotLifecycleManager(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter,
                               Supplier<Location> respawnLocator) {
        this.plugin = plugin; this.log = plugin.getLogger(); this.settings = settings;
        this.manager = manager; this.adapter = adapter; this.respawnLocator = respawnLocator;
    }

    public void start() {
        stop();
        Settings s = settings.get();
        long reconcile = s.lifecycle().reconcileIntervalTicks();
        long save = s.persistence().saveIntervalSeconds() * 20L;
        reconcileTask = Bukkit.getScheduler().runTaskTimer(plugin, this::reconcile, reconcile, reconcile);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> manager.saveAll(true), save, save);
    }

    public void stop() {
        if (reconcileTask != null) reconcileTask.cancel();
        if (saveTask != null) saveTask.cancel();
        reconcileTask = null; saveTask = null;
    }

    private boolean running;

    void reconcile() {
        if (!manager.isReady() || running) return; // re-entrancy guard
        running = true;
        try {
            long now = System.currentTimeMillis();
            int activations = 0;
            for (BotIdentity bot : new ArrayList<>(manager.all())) {
                switch (bot.state()) {
                    case ACTIVE -> checkActive(bot, now);
                    case UNLOADED -> { if (activations < MAX_ACTIVATIONS_PER_RUN && tryRestore(bot)) activations++; }
                    case RESPAWNING -> { if (activations < MAX_ACTIVATIONS_PER_RUN && tryRespawn(bot, now)) activations++; }
                    default -> { /* INACTIVE, DEAD, DISABLED: nothing to do automatically */ }
                }
            }
        } catch (RuntimeException e) {
            log.log(java.util.logging.Level.SEVERE, "Lifecycle reconcile failed (will retry next run)", e);
        } finally {
            running = false;
        }
    }

    private void checkActive(BotIdentity bot, long now) {
        Optional<LivingEntity> e = adapter.entity(bot.id());
        if (e.isEmpty()) { manager.onEntityLost(bot); return; }
        double limit = settings.get().lifecycle().despawnDistance();
        if (playerWithin(e.get().getLocation(), limit)) { bot.setOutOfRangeSinceMs(-1); return; }
        if (bot.outOfRangeSinceMs() < 0) { bot.setOutOfRangeSinceMs(now); return; }
        if (now - bot.outOfRangeSinceMs() >= settings.get().lifecycle().despawnGraceSeconds() * 1000L) {
            manager.deactivate(bot, LifecycleState.INACTIVE);
            Debug.log("Parked " + bot.name() + " as INACTIVE: no player within " + (int) limit + " blocks");
        }
    }

    private boolean tryRestore(BotIdentity bot) {
        Location loc = manager.toLocation(bot.lastLocation());
        if (loc == null) {
            if (!bot.worldMissingWarned()) {
                log.warning("Bot " + bot.name() + " is waiting: its world is not loaded or no longer exists ("
                    + (bot.lastLocation() == null ? "no location" : bot.lastLocation().worldName()) + ").");
                bot.setWorldMissingWarned(true);
            }
            return false;
        }
        World w = loc.getWorld();
        if (!w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) return false;
        if (!playerWithin(loc, settings.get().lifecycle().despawnDistance())) return false; // avoids spawn/despawn flapping
        BotManager.Result r = manager.activate(bot, loc);
        if (!r.ok()) Debug.log("Restore of " + bot.name() + " deferred: " + r.message());
        return r.ok();
    }

    private boolean tryRespawn(BotIdentity bot, long now) {
        if (now < bot.extraLong(BotIdentity.EXTRA_RESPAWN_AT, 0)) return false;
        Location loc = manager.toLocation(bot.home());
        if (loc == null || !loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)
            || !playerWithin(loc, settings.get().lifecycle().despawnDistance())) {
            loc = respawnLocator.get(); // a safe spot near some player, or null
        }
        if (loc == null) return false;
        BotManager.Result r = manager.activate(bot, loc);
        if (r.ok()) bot.removeExtra(BotIdentity.EXTRA_RESPAWN_AT);
        return r.ok();
    }

    private static boolean playerWithin(Location l, double dist) {
        double d2 = dist * dist;
        for (Player p : Bukkit.getOnlinePlayers())
            if (p.getWorld().equals(l.getWorld()) && p.getLocation().distanceSquared(l) <= d2) return true;
        return false;
    }
}
