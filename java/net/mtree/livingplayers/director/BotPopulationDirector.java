package net.mtree.livingplayers.director;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.protect.ProtectionService;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Decides when and where bots appear. Bounded work per cycle (max-attempts-per-cycle), loaded chunks only,
 * TPS-throttled, and scaled to the number of eligible real players.
 *
 * <p>HONEST LIMITATION (Stage 1): there is no pathfinding yet, so an encounter is a SAFE-LOCATION SPAWN near a
 * player (outside view, beyond min distance), not a bot that walked there. Walking arrives with the Stage 2
 * MovementController.
 */
public final class BotPopulationDirector {
    public record SpawnReport(List<String> spawned, List<String> failures) {}

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final ProtectionService protection;
    private final EncounterTracker tracker = new EncounterTracker();
    private final Random rnd = new Random();
    private BukkitTask task;
    private String lastResult = "no cycle has run yet";
    private PopulationPlanner.Plan lastPlan;

    public BotPopulationDirector(Plugin plugin, Supplier<Settings> settings, BotManager manager,
                                 BotEntityAdapter adapter, ProtectionService protection) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter; this.protection = protection;
        manager.addRemovalListener(tracker::forget);
    }

    public void start() {
        stop();
        long period = settings.get().population().spawnIntervalSeconds() * 20L;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::cycle, period, period);
    }

    public void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    public String lastResult() { return lastResult; }
    public PopulationPlanner.Plan lastPlan() { return lastPlan; }
    public int trackedEncounters() { return tracker.size(); }

    public List<Player> eligiblePlayers() {
        Settings s = settings.get();
        List<Player> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            World w = p.getWorld();
            if (s.worlds().populationAllowed(w.getName(), w.getEnvironment().name())) out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ periodic cycle

    private void cycle() {
        if (!manager.isReady()) return;
        Settings s = settings.get();
        List<Player> players = eligiblePlayers();
        double tps = Bukkit.getTPS()[0];
        PopulationPlanner.Plan plan = PopulationPlanner.plan(new PopulationPlanner.Input(s.population().enabled(), players.size(),
            manager.activeCount(), s.lifecycle().maxActiveBots(), s.population().targetActivePerPlayer(), tps, s.population().tpsThrottleBelow()));
        lastPlan = plan;
        tracker.prune(System.currentTimeMillis(), 24L * 3600_000L);
        switch (plan.action()) {
            case SPAWN -> {
                if (rnd.nextDouble() > s.population().spawnChance()) { lastResult = "skipped (spawn-chance roll)"; return; }
                List<String> failures = new ArrayList<>();
                BotIdentity b = spawnOne(players, s, false, failures);
                lastResult = b != null ? "spawned " + b.name() : "no spawn: " + String.join("; ", failures);
            }
            case DESPAWN_ONE -> lastResult = despawnFarthest(players) ? "despawned one bot (above target)" : "nothing eligible to despawn";
            case NONE -> lastResult = plan.reason();
        }
    }

    // ------------------------------------------------------------------ API used by commands/lifecycle

    /** Admin command: bypasses chance, cooldowns and TPS throttle, but never max-active, max-population or safety checks. */
    public SpawnReport spawnRandom(int amount) {
        Settings s = settings.get();
        List<Player> players = eligiblePlayers();
        List<String> spawned = new ArrayList<>(), failures = new ArrayList<>();
        for (int i = 0; i < amount; i++) {
            BotIdentity b = spawnOne(players, s, true, failures);
            if (b == null) break;
            spawned.add(b.name());
        }
        return new SpawnReport(spawned, failures);
    }

    /** A safe spot near a random eligible player, or null. Used for respawns without a usable home. */
    public Location findSpotNearAnyPlayer() {
        Settings s = settings.get();
        List<Player> players = eligiblePlayers();
        if (players.isEmpty()) return null;
        int[] budget = {s.population().maxAttemptsPerCycle()};
        return findSpot(players.get(rnd.nextInt(players.size())), players, s, budget);
    }

    // ------------------------------------------------------------------ internals

    private BotIdentity spawnOne(List<Player> players, Settings s, boolean force, List<String> failures) {
        long now = System.currentTimeMillis();
        List<Player> order = new ArrayList<>(players);
        Collections.shuffle(order, rnd);
        int[] budget = {s.population().maxAttemptsPerCycle()};
        for (Player anchor : order) {
            if (budget[0] <= 0) { failures.add("attempt budget exhausted"); break; }
            World w = anchor.getWorld();
            if (manager.activeInWorld(w) >= s.worlds().maxActiveFor(w.getName())) { failures.add("world '" + w.getName() + "' is at its max-active"); continue; }
            if (manager.activeCount() >= s.lifecycle().maxActiveBots()) { failures.add("max-active-bots reached"); break; }
            Location spot = findSpot(anchor, players, s, budget);
            if (spot == null) { failures.add("no safe spot found near " + anchor.getName()); continue; }
            BotIdentity bot = chooseBot(anchor, spot, s, force, now);
            if (bot == null) { failures.add("no bot available (population cap reached or all on cooldown)"); continue; }
            BotManager.Result r = manager.activate(bot, spot);
            if (r.ok()) { tracker.record(anchor.getUniqueId(), bot.id(), now); return bot; }
            failures.add(r.message());
        }
        return null;
    }

    private BotIdentity chooseBot(Player anchor, Location spot, Settings s, boolean force, long now) {
        Settings.Population p = s.population();
        List<BotIdentity> candidates = new ArrayList<>();
        for (BotIdentity b : manager.all()) {
            if (b.state() != LifecycleState.INACTIVE) continue;
            if (!force && !tracker.botAllowed(b.id(), now, p.botSpawnCooldownSeconds() * 1000L)) continue;
            if (!force && !tracker.pairAllowed(anchor.getUniqueId(), b.id(), now, p.encounterCooldownSeconds() * 1000L)) continue;
            candidates.add(b);
        }
        boolean canMint = manager.count() < p.maxPopulation();
        boolean mint = canMint && (candidates.isEmpty() || rnd.nextDouble() < p.newIdentityChance());
        if (mint) return manager.createBot(null, spot);
        return candidates.isEmpty() ? null : candidates.get(rnd.nextInt(candidates.size()));
    }

    private Location findSpot(Player anchor, List<Player> allPlayers, Settings s, int[] budget) {
        Settings.Population p = s.population();
        World w = anchor.getWorld();
        Location base = anchor.getLocation();
        SpawnSafety.Rules rules = new SpawnSafety.Rules(Math.max(s.spawn().minY(), w.getMinHeight() + 2),
            Math.min(s.spawn().maxY(), w.getMaxHeight() - 3), s.spawn().allowWater(), s.spawn().hazardRadius(), s.spawn().avoidArtificialRadius());
        BukkitTerrainProbe probe = new BukkitTerrainProbe(w);
        double minAny2 = (double) p.minDistanceFromAnyPlayer() * p.minDistanceFromAnyPlayer();

        while (budget[0]-- > 0) {
            Annulus.Offset o = Annulus.sample(rnd, p.minEncounterDistance(), p.maxEncounterDistance());
            int x = base.getBlockX() + o.dx(), z = base.getBlockZ() + o.dz();
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue; // never load chunks
            int y = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
            if (!SpawnSafety.check(probe, x, y, z, rules).ok()) continue;
            Location loc = new Location(w, x + 0.5, y, z + 0.5, rnd.nextFloat() * 360f, 0f);
            if (!w.getWorldBorder().isInside(loc)) continue;
            if (!protection.canSpawnAt(loc).allowed()) continue;
            if (visibleOrTooClose(loc, allPlayers, minAny2, p.avoidLineOfSight())) continue;
            return loc;
        }
        return null;
    }

    private static boolean visibleOrTooClose(Location loc, List<Player> players, double minDist2, boolean avoidLos) {
        for (Player pl : players) {
            if (!pl.getWorld().equals(loc.getWorld())) continue;
            double d2 = pl.getLocation().distanceSquared(loc);
            if (d2 < minDist2) return true;
            if (avoidLos && d2 < 128 * 128 && pl.hasLineOfSight(loc)) return true;
        }
        return false;
    }

    private boolean despawnFarthest(List<Player> players) {
        long now = System.currentTimeMillis();
        BotIdentity worst = null;
        double worstDist = -1;
        for (BotIdentity b : manager.all()) {
            if (b.state() != LifecycleState.ACTIVE) continue;
            if (now - b.extraLong(BotIdentity.EXTRA_LAST_SPAWN_AT, 0) < 60_000) continue; // let new arrivals be seen
            Optional<LivingEntity> e = adapter.entity(b.id());
            if (e.isEmpty()) continue;
            double nearest = Double.MAX_VALUE;
            for (Player p : players) if (p.getWorld().equals(e.get().getWorld())) nearest = Math.min(nearest, p.getLocation().distanceSquared(e.get().getLocation()));
            if (nearest > worstDist) { worstDist = nearest; worst = b; }
        }
        return worst != null && manager.deactivate(worst, LifecycleState.INACTIVE).ok();
    }
}
