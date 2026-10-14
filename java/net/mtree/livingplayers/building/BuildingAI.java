package net.mtree.livingplayers.building;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotInventory;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.bot.LongTermGoal;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.building.Blueprint.Placement;
import net.mtree.livingplayers.combat.CombatAI;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.director.BukkitTerrainProbe;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.nav.BotNavigator;
import net.mtree.livingplayers.nav.Pathfinder;
import net.mtree.livingplayers.storage.BotStorage;
import net.mtree.livingplayers.storage.ProjectRecord;
import net.mtree.livingplayers.survival.BlockInteractionService;
import net.mtree.livingplayers.survival.BlockInteractionService.Outcome;
import net.mtree.livingplayers.survival.Recipes;
import net.mtree.livingplayers.survival.SurvivalAI;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Builds blueprints block by block with real planks the bot gathered and crafted.
 * Flow per project: pick a flat natural site away from player builds -> persist project -> gather the planks (via
 * SurvivalAI) -> place blocks at a limited rate from a legal standing spot -> persist progress -> done (bot's home is set).
 * Progress is saved every few blocks, so a restart resumes where it stopped. The only block it ever places is planks,
 * only inside the project's box, only into empty cells or wild plants (BuildPolicy).
 */
public final class BuildingAI {
    private enum Phase { GATHER, BUILD }

    private static final class Active {
        ProjectRecord rec;
        Blueprint bp;
        List<Placement> order;
        ProgressBits bits;
        final Set<Integer> skipped = new HashSet<>();
        Phase phase = Phase.GATHER;
        long nextPlace, nextRetry;
        Location stand;
        int unsaved;
        String note = "";
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final BotNavigator navigator;
    private final CombatAI combat;
    private final SurvivalAI survival;
    private final BlockInteractionService blocks;
    private final BlueprintManager blueprints;
    private final BotStorage storage;
    private final Map<UUID, Active> active = new HashMap<>();
    private final Map<UUID, Integer> finished = new HashMap<>();
    private final Map<UUID, Long> nextAuto = new HashMap<>();
    private final Random rnd = new Random();
    private BukkitTask task;
    private long tick;

    public BuildingAI(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter, BotNavigator navigator,
                      CombatAI combat, SurvivalAI survival, BlockInteractionService blocks, BlueprintManager blueprints, BotStorage storage) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter; this.navigator = navigator;
        this.combat = combat; this.survival = survival; this.blocks = blocks; this.blueprints = blueprints; this.storage = storage;
    }

    public void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L); }
    public void stop() { if (task != null) task.cancel(); task = null; }

    /** Restores unfinished projects after a restart (progress and chosen site are exactly as saved). */
    public void load(List<ProjectRecord> rows) {
        for (ProjectRecord r : rows) {
            if (!r.active()) { finished.merge(r.botId(), 1, Integer::sum); continue; }
            Optional<Blueprint> bp = blueprints.get(r.blueprint());
            if (bp.isEmpty()) { plugin.getLogger().warning("Project " + r.id() + " needs missing blueprint '" + r.blueprint() + "'; abandoning it."); save(r, "ABANDONED", null, 0); continue; }
            active.put(r.botId(), activate(r, bp.get()));
        }
        if (!active.isEmpty()) plugin.getLogger().info("Resuming " + active.size() + " construction project(s).");
    }

    public String describe(UUID id) {
        Active a = active.get(id);
        if (a == null) return "building: " + (finished.containsKey(id) ? "finished " + finished.get(id) + " project(s)" : "no project");
        return "building: " + a.rec.blueprint() + " " + a.phase + " " + a.bits.count() + "/" + a.order.size() + " skipped=" + a.skipped.size()
            + (a.note.isEmpty() ? "" : " | " + a.note);
    }

    public String phaseLabel(UUID id) { Active a = active.get(id); return a == null ? null : a.phase.name(); }

    private java.util.function.Consumer<BotIdentity> onFinished = b -> { };
    public void setOnFinished(java.util.function.Consumer<BotIdentity> c) { onFinished = c; }

    public boolean hasProject(UUID id) { return active.containsKey(id); }

    // ------------------------------------------------------------------ starting / cancelling

    public BotManager.Result start(BotIdentity bot, String blueprintName) {
        if (!settings.get().building().enabled()) return BotManager.Result.fail("building is disabled in building.yml");
        if (active.containsKey(bot.id())) return BotManager.Result.fail(bot.name() + " already has a project (/mtreebots build " + bot.name() + " status|cancel)");
        Optional<Blueprint> bp = blueprints.get(blueprintName);
        if (bp.isEmpty()) return BotManager.Result.fail("unknown blueprint '" + blueprintName + "'. Available: " + blueprints.names());
        Optional<LivingEntity> oe = adapter.entity(bot.id());
        if (oe.isEmpty()) return BotManager.Result.fail(bot.name() + " must be active (spawned) to choose a site");

        Settings.Building cfg = settings.get().building();
        World w = oe.get().getWorld();
        Location at = oe.get().getLocation();
        int rotation = rnd.nextInt(4);
        Blueprint rotated = bp.get().rotated(rotation);
        BukkitTerrainProbe probe = new BukkitTerrainProbe(w, true);
        SiteSelector.Site site = SiteSelector.find(probe, (x, z) -> w.isChunkLoaded(x >> 4, z >> 4) ? w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1 : Integer.MIN_VALUE,
            at.getBlockX(), at.getBlockZ(), cfg.siteSearchRadius(), rotated.width(), rotated.depth(), rotated.height(), cfg.siteMargin(), cfg.maxSiteCandidates());
        if (site == null) return BotManager.Result.fail("no suitable flat, natural site within " + cfg.siteSearchRadius() + " blocks (loaded chunks only)");

        long now = System.currentTimeMillis();
        ProjectRecord rec = new ProjectRecord(UUID.randomUUID(), bot.id(), bp.get().name(), w.getUID(), site.x(), site.y(), site.z(), rotation,
            Map.of(), "", "PLANNED", 0, now, now);
        storage.saveProject(rec).whenComplete((v, err) -> { if (err != null) plugin.getLogger().log(Level.SEVERE, "Could not save construction project", err); });
        active.put(bot.id(), activate(rec, bp.get()));
        plugin.getLogger().info(bot.name() + " will build '" + bp.get().name() + "' at " + site.x() + " " + site.y() + " " + site.z() + " (rotation " + rotation + ")");
        return BotManager.Result.ok(bot.name() + " will build " + bp.get().name() + " at " + site.x() + " " + site.y() + " " + site.z());
    }

    public BotManager.Result cancel(BotIdentity bot) {
        Active a = active.remove(bot.id());
        if (a == null) return BotManager.Result.fail(bot.name() + " has no active project");
        release(bot.id());
        save(a.rec, "ABANDONED", a.bits, a.skipped.size());
        return BotManager.Result.ok("Project cancelled. Blocks already placed stay where they are (planks are never taken back).");
    }

    private Active activate(ProjectRecord r, Blueprint original) {
        Active a = new Active();
        a.rec = r;
        a.bp = original.rotated(r.rotation());
        a.order = a.bp.buildOrder();
        a.bits = ProgressBits.fromHex(a.order.size(), r.progressHex());
        return a;
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        Settings.Building cfg = settings.get().building();
        if (!manager.isReady() || !cfg.enabled()) return;
        tick += 5;
        for (BotIdentity bot : manager.all()) {
            if (bot.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> oe = adapter.entity(bot.id());
            if (oe.isEmpty()) continue;
            try {
                Active a = active.get(bot.id());
                if (a == null) { consider(bot, cfg); continue; }
                if (combat.inCombat(bot.id()) || navigator.isPaused(bot.id())) { release(bot.id()); continue; }
                run(bot, oe.get(), a, cfg);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(Level.WARNING, "Building tick failed for " + bot.name(), ex);
            }
        }
    }

    /** Auto-start rule: ladder done, builder by nature or by goal, hasn't built its allowance yet. Checked every ~30 s. */
    private void consider(BotIdentity bot, Settings.Building cfg) {
        if (!cfg.autoStart() || tick < nextAuto.getOrDefault(bot.id(), 0L)) return;
        nextAuto.put(bot.id(), tick + 600);
        if (finished.getOrDefault(bot.id(), 0) >= cfg.maxProjectsPerBot() || !survival.ladderComplete(bot.id())) return;
        boolean builder = bot.personality().get(Trait.BUILDING_PREFERENCE) >= cfg.minBuildingPreference()
            || bot.goals().contains(LongTermGoal.BUILD_HIDDEN_BASE.name()) || bot.goals().contains(LongTermGoal.ESTABLISH_VILLAGE.name());
        if (!builder) return;
        Optional<Blueprint> bp = blueprints.first();
        if (bp.isEmpty()) return;
        BotManager.Result r = start(bot, bp.get().name());
        if (!r.ok()) { nextAuto.put(bot.id(), tick + 6000); Debug.log(bot.name() + " cannot start building: " + r.message()); }
    }

    private void release(UUID id) { survival.setHold(id, false); survival.setWanted(id, null); navigator.setBusy(id, false); }

    private void run(BotIdentity bot, LivingEntity e, Active a, Settings.Building cfg) {
        World w = Bukkit.getWorld(a.rec.worldUuid());
        if (w == null || !w.equals(e.getWorld())) { a.note = "bot is not in the project's world"; release(bot.id()); return; }
        if (tick < a.nextRetry) return;
        if (a.phase == Phase.GATHER) gather(bot, a); else build(bot, e, w, a, cfg);
    }

    private void gather(BotIdentity bot, Active a) {
        survival.setHold(bot.id(), false);
        int remaining = a.order.size() - a.bits.count() - a.skipped.size();
        int have = Recipes.count(counts(bot.inventory()), "PLANKS");
        if (remaining <= 0) { a.phase = Phase.BUILD; return; } // build() will finish the project
        if (have >= Math.min(remaining, 32)) { a.phase = Phase.BUILD; a.note = "materials ready (" + have + " planks)"; return; }
        survival.setWanted(bot.id(), Map.of("PLANKS", remaining));
        a.note = "gathering planks: " + have + "/" + remaining;
    }

    private void build(BotIdentity bot, LivingEntity e, World w, Active a, Settings.Building cfg) {
        survival.setWanted(bot.id(), null);
        survival.setHold(bot.id(), true);
        navigator.setBusy(bot.id(), true); // no wandering away from the site
        int idx = nextIndex(a);
        if (idx < 0) { finish(bot, e, w, a); return; } // nothing left to place (also covers a fully built resumed project)
        BotInventory inv = bot.inventory();
        Material mat = pickPlanks(inv, a.rec.materials());
        if (mat == null) { a.phase = Phase.GATHER; a.note = "out of planks"; return; }
        Placement p = a.order.get(idx);
        Block target = w.getBlockAt(a.rec.ox() + p.x(), a.rec.oy() + p.y(), a.rec.oz() + p.z());
        if (!w.isChunkLoaded(target.getX() >> 4, target.getZ() >> 4)) { a.note = "waiting for chunk"; return; }

        if (target.getType().name().endsWith("_PLANKS")) { a.bits.set(idx); return; } // already there (resume / earlier unsaved placement)

        if (blocks.placeReach(e, target) != null) { // not in reach/sight: (re)choose a legal standing spot and walk there
            BotNavigator.State st = navigator.stateOf(bot.id());
            if (a.stand == null || st == BotNavigator.State.IDLE || st == BotNavigator.State.FAILED) {
                a.stand = findStand(e, w, a, target);
                if (a.stand == null) { skip(a, idx, "no standing spot for cell " + p); return; }
                navigator.setBusy(bot.id(), true);
                navigator.pursue(bot, a.stand, false, 0.8);
            }
            return;
        }
        navigator.halt(bot.id());
        if (tick < a.nextPlace) return;

        Outcome o = blocks.placeBuild(bot, e, target, mat, true);
        switch (o.status()) {
            case DONE -> {
                a.bits.set(idx);
                a.nextPlace = tick + cfg.placeIntervalTicks();
                if (++a.unsaved >= 8) { save(a.rec, "BUILDING", a.bits, a.skipped.size()); a.unsaved = 0; }
                a.note = "placed " + a.bits.count() + "/" + a.order.size();
            }
            case OUT_OF_REACH, NO_LINE_OF_SIGHT -> a.stand = null;
            case DENIED -> { a.note = "denied: " + o.detail(); a.nextRetry = tick + 600; Debug.log(bot.name() + " build denied: " + o.detail()); }
            case INVALID -> {
                if (o.detail().startsWith("bot has no")) a.phase = Phase.GATHER;
                else if (o.detail().contains("entity") || o.detail().contains("inside the bot")) a.nextRetry = tick + 40;
                else skip(a, idx, o.detail());
            }
            default -> a.nextRetry = tick + 20;
        }
        if (a.skipped.size() > cfg.maxSkipped()) abandon(bot, a, "too many obstructed cells");
    }

    private int nextIndex(Active a) {
        for (int i = 0; i < a.order.size(); i++) if (!a.bits.get(i) && !a.skipped.contains(i)) return i;
        return -1;
    }

    private void skip(Active a, int idx, String why) { a.skipped.add(idx); a.note = "skipped a cell: " + why; a.stand = null; }

    private void abandon(BotIdentity bot, Active a, String why) {
        active.remove(bot.id());
        release(bot.id());
        save(a.rec, "ABANDONED", a.bits, a.skipped.size());
        plugin.getLogger().warning(bot.name() + " abandoned its construction project: " + why);
    }

    private void finish(BotIdentity bot, LivingEntity e, World w, Active a) {
        active.remove(bot.id());
        release(bot.id());
        save(a.rec, "DONE", a.bits, a.skipped.size());
        finished.merge(bot.id(), 1, Integer::sum);
        Location home = new Location(w, a.rec.ox() + a.bp.width() / 2.0, a.rec.oy(), a.rec.oz() + a.bp.depth() / 2.0);
        bot.setHome(BotManager.fromLocation(home));
        onFinished.accept(bot);
        plugin.getLogger().info(bot.name() + " finished building '" + a.rec.blueprint() + "' (" + a.bits.count() + " blocks, " + a.skipped.size() + " skipped). Home set.");
    }

    // ------------------------------------------------------------------ helpers

    /** A standable cell near the project (inside or outside the footprint) that is NOT a cell still to be built and from which the target is reachable. */
    private Location findStand(LivingEntity e, World w, Active a, Block target) {
        Set<String> pending = new HashSet<>();
        for (int i = 0; i < a.order.size(); i++)
            if (!a.bits.get(i)) { Placement p = a.order.get(i); pending.add((a.rec.ox() + p.x()) + "," + (a.rec.oy() + p.y()) + "," + (a.rec.oz() + p.z())); }
        BukkitTerrainProbe probe = new BukkitTerrainProbe(w);
        double reach = settings.get().survival().reach() - 0.4;
        Location me = e.getLocation();
        Location best = null;
        double bestD = Double.MAX_VALUE;
        for (int x = a.rec.ox() - 3; x < a.rec.ox() + a.bp.width() + 3; x++)
            for (int z = a.rec.oz() - 3; z < a.rec.oz() + a.bp.depth() + 3; z++)
                for (int y = a.rec.oy() - 1; y <= a.rec.oy() + 1; y++) {
                    if (!Pathfinder.standable(probe, x, y, z)) continue;
                    if (pending.contains(x + "," + y + "," + z) || pending.contains(x + "," + (y + 1) + "," + z)) continue; // never stand where a block will go
                    double dx = target.getX() + 0.5 - (x + 0.5), dy = target.getY() + 0.5 - (y + 1.62), dz = target.getZ() + 0.5 - (z + 0.5);
                    if (Math.sqrt(dx * dx + dy * dy + dz * dz) > reach) continue;
                    if (x == target.getX() && z == target.getZ()) continue;
                    double d = (x + 0.5 - me.getX()) * (x + 0.5 - me.getX()) + (z + 0.5 - me.getZ()) * (z + 0.5 - me.getZ());
                    if (d < bestD) { bestD = d; best = new Location(w, x + 0.5, y, z + 0.5); }
                }
        return best;
    }

    private static Material pickPlanks(BotInventory inv, Map<String, String> chosen) {
        Material fav = null;
        String f = chosen.get("PLANKS");
        if (f != null) { try { fav = Material.valueOf(f); } catch (IllegalArgumentException ignored) { /* fall through */ } }
        if (fav != null && inv.count(fav) > 0) return fav;
        Material best = null;
        int bestN = 0;
        for (Map.Entry<String, Integer> en : counts(inv).entrySet()) {
            if (!Recipes.matches("PLANKS", en.getKey()) || en.getValue() <= bestN) continue;
            try { best = Material.valueOf(en.getKey()); bestN = en.getValue(); } catch (IllegalArgumentException ignored) { /* unknown */ }
        }
        return best;
    }

    private static Map<String, Integer> counts(BotInventory inv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < BotInventory.MAIN_SLOTS; i++) { ItemStack s = inv.get(i); if (s != null) m.merge(s.getType().name(), s.getAmount(), Integer::sum); }
        return m;
    }

    private void save(ProjectRecord r, String status, ProgressBits bits, int skipped) {
        ProjectRecord n = new ProjectRecord(r.id(), r.botId(), r.blueprint(), r.worldUuid(), r.ox(), r.oy(), r.oz(), r.rotation(), r.materials(),
            bits == null ? r.progressHex() : bits.toHex(), status, skipped, r.createdAt(), System.currentTimeMillis());
        Active a = active.get(r.botId());
        if (a != null && a.rec.id().equals(r.id())) a.rec = n;
        storage.saveProject(n).whenComplete((v, err) -> { if (err != null) plugin.getLogger().log(Level.SEVERE, "Could not save construction project " + r.id(), err); });
    }
}
