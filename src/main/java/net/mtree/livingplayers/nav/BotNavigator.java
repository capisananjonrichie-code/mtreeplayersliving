package net.mtree.livingplayers.nav;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.director.Annulus;
import net.mtree.livingplayers.director.BukkitTerrainProbe;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.nav.Pathfinder.Limits;
import net.mtree.livingplayers.nav.Pathfinder.Node;
import net.mtree.livingplayers.nav.Pathfinder.PathResult;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Moves ACTIVE bots along A* paths. A Mannequin has no navigation of its own, so movement is simulated by setting
 * the entity's horizontal velocity toward the next waypoint each tick and jumping for step-ups; the server's normal
 * physics (gravity, collisions, fall damage) still apply. No teleporting.
 *
 * <p>UNVERIFIED ON A LIVE SERVER: that setVelocity moves a Mannequin smoothly. See docs/TEST_PLAN.md (T2.x).
 * Cost control: at most {@code max-path-calcs-per-tick} searches per tick, each bounded; chunks are never loaded.
 */
public final class BotNavigator {
    public enum State { IDLE, PLANNING, MOVING, FAILED }

    private static final class Nav {
        State state = State.IDLE;
        World world;
        Node goal;
        List<Node> path = List.of();
        int idx;
        double arriveRadius = 0.6;
        boolean sprint;
        int repaths;
        final StuckDetector stuck = new StuckDetector(4, 0.6);
        boolean paused;
        boolean combat; // combat AI is steering: no wandering
        boolean busy;   // a task (gathering/crafting) owns the bot: no wandering
        UUID follow;
        long nextWanderTick;
        long nextFollowTick;
        double jitter = 1.0;
        String note = "";
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final Map<UUID, Nav> navs = new HashMap<>();
    private final ArrayDeque<UUID> planQueue = new ArrayDeque<>();
    private final Random rnd = new Random();
    private BukkitTask task;
    private long tick;

    public BotNavigator(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter;
    }

    public void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }
    public void stop() { if (task != null) task.cancel(); task = null; }

    // ------------------------------------------------------------------ commands API

    public BotManager.Result goTo(BotIdentity bot, Location target) {
        Optional<LivingEntity> e = adapter.entity(bot.id());
        if (e.isEmpty()) return BotManager.Result.fail(bot.name() + " has no live entity");
        if (target.getWorld() == null || !target.getWorld().equals(e.get().getWorld())) return BotManager.Result.fail("different world");
        Node goal = standableNear(new BukkitTerrainProbe(target.getWorld()), target.getBlockX(), target.getBlockY(), target.getBlockZ());
        if (goal == null) return BotManager.Result.fail("target is not a standable spot (or its chunk is not loaded)");
        Nav nv = nav(bot.id());
        nv.follow = null;
        nv.paused = false;
        begin(bot.id(), nv, target.getWorld(), goal, e.get().getLocation().distanceSquared(target) > 100, 0.7);
        return BotManager.Result.ok(bot.name() + " is heading to " + goal.x() + " " + goal.y() + " " + goal.z());
    }

    public BotManager.Result follow(BotIdentity bot, Player p) {
        if (adapter.entity(bot.id()).isEmpty()) return BotManager.Result.fail(bot.name() + " has no live entity");
        Nav nv = nav(bot.id());
        nv.follow = p.getUniqueId();
        nv.paused = false;
        nv.nextFollowTick = 0;
        return BotManager.Result.ok(bot.name() + " will follow " + p.getName());
    }

    /** Cancels the current route and follow, and pauses wandering until {@link #resume}. */
    public void stop(BotIdentity bot) {
        Nav nv = nav(bot.id());
        nv.follow = null;
        nv.paused = true;
        nv.state = State.IDLE;
        nv.path = List.of();
        nv.note = "stopped by command";
    }

    public void resume(BotIdentity bot) {
        Nav nv = nav(bot.id());
        nv.paused = false;
        nv.nextWanderTick = tick + 20;
    }

    /** While true the bot never starts a wander trip (CombatAI owns its movement). */
    public void setCombatControl(UUID id, boolean on) { nav(id).combat = on; }

    public void setBusy(UUID id, boolean on) { nav(id).busy = on; }

    public boolean isBusy(UUID id) { Nav nv = navs.get(id); return nv != null && nv.busy; }

    public boolean isPaused(UUID id) { Nav nv = navs.get(id); return nv != null && nv.paused; }

    public State stateOf(UUID id) { Nav nv = navs.get(id); return nv == null ? State.IDLE : nv.state; }

    /** Cancels the current route WITHOUT pausing wandering. */
    public void halt(UUID id) {
        Nav nv = navs.get(id);
        if (nv != null && (nv.state == State.MOVING || nv.state == State.PLANNING)) { nv.state = State.IDLE; nv.path = List.of(); }
    }

    /** Combat/flee routing: plan toward a spot, finishing within {@code radius}. Returns false if the spot is unusable. */
    public boolean pursue(BotIdentity bot, Location target, boolean sprint, double radius) {
        Optional<LivingEntity> e = adapter.entity(bot.id());
        if (e.isEmpty() || target.getWorld() == null || !target.getWorld().equals(e.get().getWorld())) return false;
        Node goal = standableNear(new BukkitTerrainProbe(target.getWorld()), target.getBlockX(), target.getBlockY(), target.getBlockZ());
        if (goal == null) return false;
        Nav nv = nav(bot.id());
        begin(bot.id(), nv, target.getWorld(), goal, sprint, radius);
        return true;
    }

    public String describe(UUID id) {
        Nav nv = navs.get(id);
        if (nv == null) return "navigation: no state yet";
        return "navigation: " + nv.state + (nv.paused ? " (paused)" : "") + (nv.follow != null ? " following=" + nv.follow : "")
            + (nv.goal != null ? " goal=" + nv.goal.x() + "," + nv.goal.y() + "," + nv.goal.z() : "")
            + " waypoint=" + nv.idx + "/" + nv.path.size() + " repaths=" + nv.repaths + (nv.note.isEmpty() ? "" : " | " + nv.note);
    }

    // ------------------------------------------------------------------ tick

    private Nav nav(UUID id) {
        return navs.computeIfAbsent(id, k -> {
            Nav n = new Nav();
            n.nextWanderTick = tick + 20 + rnd.nextInt(80);
            return n;
        });
    }

    private void tick() {
        if (!manager.isReady()) return;
        Settings.Navigation cfg = settings.get().navigation();
        if (!cfg.enabled()) return;
        tick++;
        navs.keySet().removeIf(id -> manager.byId(id).map(b -> b.state() != LifecycleState.ACTIVE).orElse(true));

        int budget = cfg.maxPathCalcsPerTick();
        while (budget > 0 && !planQueue.isEmpty()) {
            UUID id = planQueue.poll();
            Nav nv = navs.get(id);
            if (nv == null || nv.state != State.PLANNING) continue;
            plan(id, nv, cfg);
            budget--;
        }

        for (BotIdentity b : manager.all()) {
            if (b.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> oe = adapter.entity(b.id());
            if (oe.isEmpty()) continue;
            Nav nv = nav(b.id());
            LivingEntity e = oe.get();
            if (nv.follow != null) updateFollow(b, nv, e, cfg);
            else if (nv.state == State.IDLE || nv.state == State.FAILED) maybeWander(b, nv, e, cfg);
            if (nv.state == State.MOVING) move(b, nv, e, cfg);
        }
    }

    private void begin(UUID id, Nav nv, World w, Node goal, boolean sprint, double radius) {
        nv.world = w; nv.goal = goal; nv.sprint = sprint; nv.arriveRadius = radius;
        nv.repaths = 0; nv.state = State.PLANNING; nv.note = "";
        if (!planQueue.contains(id)) planQueue.add(id);
    }

    private void plan(UUID id, Nav nv, Settings.Navigation cfg) {
        Optional<LivingEntity> oe = adapter.entity(id);
        if (oe.isEmpty() || nv.world == null || !oe.get().getWorld().equals(nv.world)) { fail(nv, cfg, "entity gone or changed world"); return; }
        BukkitTerrainProbe probe = new BukkitTerrainProbe(nv.world);
        Location l = oe.get().getLocation();
        Node start = standableNear(probe, l.getBlockX(), l.getBlockY(), l.getBlockZ());
        if (start == null) { fail(nv, cfg, "start position is not standable (water/air?)"); return; }
        PathResult r = Pathfinder.find(probe, start, nv.goal, new Limits(cfg.maxPathNodes(), cfg.maxPathDistance(), cfg.maxDrop()));
        if (!r.usable()) { fail(nv, cfg, "no path: " + r.status() + " (expanded " + r.expanded() + ")"); return; }
        nv.path = r.path(); nv.idx = Math.min(1, r.path().size()); nv.stuck.reset(); nv.state = State.MOVING;
        nv.note = r.status() + " len=" + r.path().size() + " expanded=" + r.expanded();
        Debug.log("nav " + id + ": " + nv.note);
    }

    private void move(BotIdentity b, Nav nv, LivingEntity e, Settings.Navigation cfg) {
        Location cur = e.getLocation();
        double gd = Math.hypot(nv.goal.x() + 0.5 - cur.getX(), nv.goal.z() + 0.5 - cur.getZ());
        if (gd <= nv.arriveRadius && Math.abs(cur.getY() - nv.goal.y()) < 1.5) { arrive(nv, cfg); return; }
        if (nv.idx >= nv.path.size()) { arrive(nv, cfg); return; }

        Node next = nv.path.get(nv.idx);
        double dx = next.x() + 0.5 - cur.getX(), dz = next.z() + 0.5 - cur.getZ();
        double dist = Math.hypot(dx, dz);
        if (dist < 0.3 && Math.abs(cur.getY() - next.y()) < 1.1) { nv.idx++; return; }

        if (tick % 10 == 0 && nv.stuck.sample(cur.getX(), cur.getY(), cur.getZ())) { recover(b, nv, e, cfg, "stuck"); return; }
        if (dist > 3.0 || Math.abs(cur.getY() - next.y()) > 2.5) { recover(b, nv, e, cfg, "off path"); return; }

        if (tick % 40 == 0) nv.jitter = 0.9 + rnd.nextDouble() * 0.2; // slightly irregular pace
        double speed = (nv.sprint ? cfg.sprintSpeed() : cfg.walkSpeed()) * nv.jitter;
        Vector v = e.getVelocity();
        v.setX(dx / Math.max(dist, 1e-6) * speed);
        v.setZ(dz / Math.max(dist, 1e-6) * speed);
        if (next.y() > cur.getBlockY() && e.isOnGround()) v.setY(0.42); // vanilla jump impulse
        e.setVelocity(v);

        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float delta = ((want - cur.getYaw() + 540f) % 360f) - 180f;
        e.setRotation(cur.getYaw() + Math.max(-30f, Math.min(30f, delta)), 0f);
    }

    private void recover(BotIdentity b, Nav nv, LivingEntity e, Settings.Navigation cfg, String why) {
        if (++nv.repaths > cfg.repathLimit()) { fail(nv, cfg, "gave up after " + why); return; }
        nv.note = "replanning (" + why + " #" + nv.repaths + ")";
        if (why.equals("stuck") && e.isOnGround()) { Vector v = e.getVelocity(); v.setY(0.42); e.setVelocity(v); } // try hopping free
        nv.state = State.PLANNING;
        nv.stuck.reset();
        if (!planQueue.contains(b.id())) planQueue.add(b.id());
    }

    private void arrive(Nav nv, Settings.Navigation cfg) {
        nv.state = State.IDLE; nv.path = List.of(); nv.note = "arrived";
        nv.nextWanderTick = tick + pause(cfg);
    }

    private void fail(Nav nv, Settings.Navigation cfg, String why) {
        nv.state = State.FAILED; nv.path = List.of(); nv.note = why;
        nv.nextWanderTick = tick + pause(cfg);
        Debug.log("nav failed: " + why);
    }

    private long pause(Settings.Navigation cfg) {
        int lo = cfg.wanderPauseMinSeconds(), hi = cfg.wanderPauseMaxSeconds();
        return 20L * (lo + rnd.nextInt(Math.max(1, hi - lo + 1)));
    }

    private void updateFollow(BotIdentity b, Nav nv, LivingEntity e, Settings.Navigation cfg) {
        Player p = Bukkit.getPlayer(nv.follow);
        if (p == null || !p.isOnline() || !p.getWorld().equals(e.getWorld())) {
            nv.follow = null; nv.state = State.IDLE; nv.path = List.of(); nv.note = "follow target left";
            return;
        }
        if (tick < nv.nextFollowTick) return;
        nv.nextFollowTick = tick + 20;
        double d = e.getLocation().distance(p.getLocation());
        if (d <= cfg.followDistance()) { if (nv.state == State.MOVING) { nv.state = State.IDLE; nv.path = List.of(); } return; }
        Location pl = p.getLocation();
        Node g = standableNear(new BukkitTerrainProbe(p.getWorld()), pl.getBlockX(), pl.getBlockY(), pl.getBlockZ());
        if (g != null) begin(b.id(), nv, p.getWorld(), g, d > 10, cfg.followDistance());
    }

    private void maybeWander(BotIdentity b, Nav nv, LivingEntity e, Settings.Navigation cfg) {
        if (!cfg.wanderEnabled() || nv.paused || nv.combat || nv.busy || tick < nv.nextWanderTick) return;
        nv.nextWanderTick = tick + pause(cfg); // if no destination is found now, try again after a pause
        World w = e.getWorld();
        Location l = e.getLocation();
        BukkitTerrainProbe probe = new BukkitTerrainProbe(w);
        for (int attempt = 0; attempt < 3; attempt++) {
            Annulus.Offset o = Annulus.sample(rnd, cfg.wanderRadiusMin(), cfg.wanderRadiusMax());
            int x = l.getBlockX() + o.dx(), z = l.getBlockZ() + o.dz();
            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
            Node g = standableNear(probe, x, w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1, z);
            if (g == null || nearPlayer(w, g, cfg.avoidPlayerDistance())) continue;
            begin(b.id(), nv, w, g, false, 0.7);
            return;
        }
        nv.note = "wander: no reachable spot found this time";
    }

    private static boolean nearPlayer(World w, Node g, int dist) {
        if (dist <= 0) return false;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getWorld().equals(w)) continue;
            Location pl = p.getLocation();
            if (Math.hypot(pl.getX() - g.x(), pl.getZ() - g.z()) < dist) return true;
        }
        return false;
    }

    private static Node standableNear(BukkitTerrainProbe probe, int x, int y, int z) {
        for (int dy : new int[] {0, -1, 1}) if (Pathfinder.standable(probe, x, y + dy, z)) return new Node(x, y + dy, z);
        return null;
    }
}
