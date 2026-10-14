package net.mtree.livingplayers.survival;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotInventory;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.combat.BotEquipmentService;
import net.mtree.livingplayers.combat.CombatAI;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.director.BukkitTerrainProbe;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.nav.BotNavigator;
import net.mtree.livingplayers.nav.Pathfinder;
import net.mtree.livingplayers.protect.BlockPolicy;
import net.mtree.livingplayers.protect.ProtectionService;
import net.mtree.livingplayers.survival.BlockInteractionService.Outcome;
import net.mtree.livingplayers.survival.Recipes.Recipe;
import net.mtree.livingplayers.survival.SurvivalPlanner.Step;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Autonomous gathering and crafting for ACTIVE bots that are not fighting, eating or stopped by command.
 * Loop: plan (pure SurvivalPlanner) -> find a block (bounded scan, policy-checked) -> walk there -> mine -> craft/place.
 * On failure the bot backs off and WANDERS (which explores new ground) before searching again. Never loads chunks.
 */
public final class SurvivalAI {
    private enum Phase { PLAN, GOTO, MINING, CRAFTING, BACKOFF }

    private static final class SV {
        Phase phase = Phase.PLAN;
        Block target;
        Location stand;
        Step step;
        long until, nextPlan, nextScan, goStarted;
        int failures, goRetries;
        String note = "";
        final Map<String, Long> blacklist = new HashMap<>();
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final BotNavigator navigator;
    private final CombatAI combat;
    private final BlockInteractionService blocks;
    private final ProtectionService protection;
    private final BotEquipmentService equipment;
    private final Map<UUID, SV> states = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> wanted = new HashMap<>();
    private final java.util.Set<UUID> hold = new java.util.HashSet<>();
    private final java.util.Set<UUID> ladderDone = new java.util.HashSet<>();
    private BukkitTask task;
    private long tick;

    public SurvivalAI(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter, BotNavigator navigator,
                      CombatAI combat, BlockInteractionService blocks, ProtectionService protection, BotEquipmentService equipment) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter; this.navigator = navigator;
        this.combat = combat; this.blocks = blocks; this.protection = protection; this.equipment = equipment;
    }

    public void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }
    public void stop() { if (task != null) task.cancel(); task = null; }

    /** Another AI (building) asks this bot to also obtain these item counts once its tool ladder is done. Empty/null clears. */
    public void setWanted(UUID id, Map<String, Integer> items) { if (items == null || items.isEmpty()) wanted.remove(id); else wanted.put(id, Map.copyOf(items)); }

    /** While true, gathering/crafting leaves the bot alone (building owns its movement). */
    public void setHold(UUID id, boolean on) { if (on) hold.add(id); else hold.remove(id); }

    /** e.g. "GATHER:LOG", "CRAFT:STONE_PICKAXE", or null when idle/backing off. */
    public String stepLabel(UUID id) {
        SV sv = states.get(id);
        return sv == null || sv.step == null || sv.phase == Phase.BACKOFF || sv.step.type() == SurvivalPlanner.Type.DONE ? null : sv.step.type() + ":" + sv.step.what();
    }

    private java.util.function.Consumer<BotIdentity> onLadderDone = b -> { };
    public void setOnLadderDone(java.util.function.Consumer<BotIdentity> c) { onLadderDone = c; }

    public boolean ladderComplete(UUID id) { return ladderDone.contains(id); }

    public String describe(UUID id) {
        SV sv = states.get(id);
        return sv == null ? "survival: idle" : "survival: " + sv.phase + (sv.step != null ? " step=" + sv.step.type() + ":" + sv.step.what() + "x" + sv.step.amount() : "")
            + " failures=" + sv.failures + (sv.note.isEmpty() ? "" : " | " + sv.note);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        Settings.Survival cfg = settings.get().survival();
        if (!manager.isReady() || !cfg.enabled()) return;
        tick++;
        states.keySet().removeIf(id -> manager.byId(id).map(b -> b.state() != LifecycleState.ACTIVE).orElse(true));
        hold.removeIf(id -> manager.byId(id).isEmpty());
        for (BotIdentity bot : manager.all()) {
            if (bot.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> oe = adapter.entity(bot.id());
            if (oe.isEmpty()) continue;
            SV sv = states.computeIfAbsent(bot.id(), k -> new SV());
            try {
                if (hold.contains(bot.id())) { // another AI (building) owns the bot: drop any half-finished action but DO NOT touch its busy flag
                    if (sv.phase != Phase.PLAN && sv.phase != Phase.BACKOFF) { blocks.cancel(bot.id()); sv.phase = Phase.PLAN; sv.target = null; }
                    continue;
                }
                if (combat.inCombat(bot.id()) || navigator.isPaused(bot.id())) { stand(bot, sv); continue; }
                handle(bot, oe.get(), sv, cfg);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Survival tick failed for " + bot.name(), ex);
                backoff(bot, sv, cfg, "internal error");
            }
        }
    }

    /** Fighting/stopped: drop any half-finished action and release movement. */
    private void stand(BotIdentity bot, SV sv) {
        if (sv.phase != Phase.PLAN && sv.phase != Phase.BACKOFF) { blocks.cancel(bot.id()); sv.phase = Phase.PLAN; sv.target = null; }
        navigator.setBusy(bot.id(), false);
    }

    private void handle(BotIdentity bot, LivingEntity e, SV sv, Settings.Survival cfg) {
        switch (sv.phase) {
            case PLAN -> plan(bot, e, sv, cfg);
            case GOTO -> goTo(bot, e, sv, cfg);
            case MINING -> mining(bot, e, sv, cfg);
            case CRAFTING -> { if (tick >= sv.until) craft(bot, e, sv, cfg); }
            case BACKOFF -> { if (tick >= sv.until) { sv.phase = Phase.PLAN; sv.failures = Math.min(sv.failures, 6); } }
        }
    }

    // ------------------------------------------------------------------ phases

    private void plan(BotIdentity bot, LivingEntity e, SV sv, Settings.Survival cfg) {
        if (tick < sv.nextPlan) return;
        sv.nextPlan = tick + 20;
        BotInventory inv = bot.inventory();
        Map<String, Integer> have = counts(inv);
        SurvivalPlanner.Env env = new SurvivalPlanner.Env(tableNearby(e, 4));
        Step step = SurvivalPlanner.nextStep(have, env);
        if (step.type() == SurvivalPlanner.Type.DONE) {
            if (ladderDone.add(bot.id())) onLadderDone.accept(bot);
            Map<String, Integer> w = wanted.get(bot.id());
            if (w != null && !w.isEmpty()) step = SurvivalPlanner.nextStepForItems(have, env, w);
        }
        sv.step = step;
        switch (step.type()) {
            case DONE -> { sv.note = "nothing to do"; navigator.setBusy(bot.id(), false); }
            case BLOCKED -> backoff(bot, sv, cfg, "blocked: " + step.what());
            case GATHER -> {
                if (inv.freeSlots() == 0 && !makeRoom(bot, e)) { backoff(bot, sv, cfg, "inventory full of valuables"); return; }
                if (tick < sv.nextScan) return;
                sv.nextScan = tick + cfg.scanIntervalSeconds() * 20L;
                Block t = findTarget(bot, e, step.what(), sv, cfg);
                if (t == null) { backoff(bot, sv, cfg, "no " + step.what() + " found nearby that is allowed to be taken"); return; }
                sv.target = t;
                sv.goRetries = 0; sv.goStarted = tick;
                navigator.setBusy(bot.id(), true);
                navigator.pursue(bot, sv.stand, false, 0.8);
                sv.phase = Phase.GOTO;
                sv.note = "going to " + t.getType().name() + " at " + t.getX() + "," + t.getY() + "," + t.getZ();
            }
            case CRAFT -> { navigator.halt(bot.id()); navigator.setBusy(bot.id(), true); sv.until = tick + cfg.craftTicks(); sv.phase = Phase.CRAFTING; sv.note = "crafting " + step.what(); }
            case PLACE_TABLE -> placeTable(bot, e, sv, cfg);
        }
    }

    private void goTo(BotIdentity bot, LivingEntity e, SV sv, Settings.Survival cfg) {
        if (sv.target == null || !matchesGroup(sv.step, sv.target.getType().name())) { sv.phase = Phase.PLAN; return; }
        if (blocks.inReachAndSight(e, sv.target)) { navigator.halt(bot.id()); sv.phase = Phase.MINING; return; }
        BotNavigator.State st = navigator.stateOf(bot.id());
        if (st == BotNavigator.State.IDLE || st == BotNavigator.State.FAILED) {
            if (++sv.goRetries > 2) { ban(sv, sv.target); sv.phase = Phase.PLAN; sv.note = "could not reach target"; return; }
            navigator.pursue(bot, sv.stand, false, 0.8);
        }
        if (tick - sv.goStarted > 20 * 40L) { ban(sv, sv.target); sv.phase = Phase.PLAN; sv.note = "timed out walking"; }
    }

    private void mining(BotIdentity bot, LivingEntity e, SV sv, Settings.Survival cfg) {
        Outcome o = blocks.mine(bot, e, sv.target);
        switch (o.status()) {
            case IN_PROGRESS -> { /* keep swinging */ }
            case DONE -> { sv.failures = 0; sv.phase = Phase.PLAN; sv.nextPlan = 0; sv.nextScan = 0; sv.note = "mined " + o.detail(); }
            case OUT_OF_REACH, NO_LINE_OF_SIGHT -> { sv.goRetries = 0; sv.goStarted = tick; navigator.pursue(bot, sv.stand, false, 0.8); sv.phase = Phase.GOTO; }
            case NO_SPACE -> { if (!makeRoom(bot, e)) backoff(bot, sv, cfg, "inventory full of valuables"); else sv.phase = Phase.PLAN; }
            case DENIED, INVALID -> { ban(sv, sv.target); blocks.cancel(bot.id()); sv.phase = Phase.PLAN; sv.nextPlan = 0; sv.note = o.status() + ": " + o.detail(); Debug.log(bot.name() + " " + sv.note); }
        }
    }

    private void craft(BotIdentity bot, LivingEntity e, SV sv, Settings.Survival cfg) {
        Step step = sv.step;
        sv.phase = Phase.PLAN; sv.nextPlan = 0;
        if (step == null || step.type() != SurvivalPlanner.Type.CRAFT) return;
        Recipe r = Recipes.ALL.stream().filter(x -> x.id().equals(step.what())).findFirst().orElse(null);
        if (r == null) { backoff(bot, sv, cfg, "unknown recipe"); return; }
        if (r.needsTable() && !tableNearby(e, 4)) { sv.note = "no crafting table within reach"; return; }

        BotInventory inv = bot.inventory();
        Map<String, Integer> before = counts(inv), after = new HashMap<>(before);
        if (!Recipes.apply(after, r, step.amount())) { backoff(bot, sv, cfg, "missing ingredients for " + r.id()); return; }
        // validate every item name BEFORE touching the inventory, so a failure can never half-craft
        Map<Material, Integer> remove = new TreeMap<>(), add = new TreeMap<>();
        try {
            for (String name : unionKeys(before, after)) {
                int delta = after.getOrDefault(name, 0) - before.getOrDefault(name, 0);
                if (delta < 0) remove.put(Material.valueOf(name), -delta);
                else if (delta > 0) add.put(Material.valueOf(name), delta);
            }
        } catch (IllegalArgumentException ex) { backoff(bot, sv, cfg, "unknown item in recipe"); return; }
        if (inv.freeSlots() < 1 + add.size()) { if (!makeRoom(bot, e)) { backoff(bot, sv, cfg, "no room for crafting result"); return; } }
        remove.forEach(inv::remove);
        for (Map.Entry<Material, Integer> a : add.entrySet()) {
            ItemStack left = inv.add(new ItemStack(a.getKey(), a.getValue()));
            if (left != null) e.getWorld().dropItemNaturally(e.getLocation(), left); // conserved
        }
        bot.markDirty();
        equipment.refresh(bot);
        adapter.pushState(bot);
        sv.failures = 0; sv.note = "crafted " + r.id() + " x" + step.amount();
        Debug.log(bot.name() + " " + sv.note);
    }

    private void placeTable(BotIdentity bot, LivingEntity e, SV sv, Settings.Survival cfg) {
        Block feet = e.getLocation().getBlock();
        int[][] ring = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
        String last = "no suitable spot";
        for (int[] o : ring) {
            Block cand = feet.getRelative(o[0], 0, o[1]);
            Outcome out = blocks.placeTable(bot, e, cand);
            if (out.status() == BlockInteractionService.Status.DONE) { sv.note = "placed a crafting table"; sv.failures = 0; sv.nextPlan = 0; return; }
            last = out.detail();
            if (out.status() == BlockInteractionService.Status.DENIED) break; // policy refuses everywhere around here
        }
        backoff(bot, sv, cfg, "could not place crafting table: " + last);
    }

    private void backoff(BotIdentity bot, SV sv, Settings.Survival cfg, String why) {
        sv.failures++;
        sv.phase = Phase.BACKOFF;
        sv.until = tick + cfg.failureBackoffSeconds() * 20L;
        sv.note = why;
        sv.target = null;
        blocks.cancel(bot.id());
        navigator.setBusy(bot.id(), false); // free to wander and explore before the next attempt
        Debug.log(bot.name() + " survival backoff: " + why);
    }

    // ------------------------------------------------------------------ searching

    private static boolean matchesGroup(Step step, String block) {
        if (step == null || step.type() != SurvivalPlanner.Type.GATHER) return false;
        return step.what().equals("LOG") ? BlockPolicy.isNaturalLog(block) : step.what().equals("COBBLESTONE") && block.equals("STONE");
    }

    private Block findTarget(BotIdentity bot, LivingEntity e, String group, SV sv, Settings.Survival cfg) {
        Predicate<String> match = group.equals("LOG") ? BlockPolicy::isNaturalLog : n -> n.equals("STONE");
        World w = e.getWorld();
        Location base = e.getLocation();
        int R = cfg.searchRadius(), budget = cfg.scanBudget();
        long nowTick = tick;
        sv.blacklist.values().removeIf(t -> t < nowTick);

        record Cand(Block b, double d2) {}
        List<Cand> found = new ArrayList<>();
        scan:
        for (int dy = -4; dy <= 6; dy++)
            for (int dx = -R; dx <= R; dx++)
                for (int dz = -R; dz <= R; dz++) {
                    if (--budget < 0) break scan;
                    int x = base.getBlockX() + dx, y = base.getBlockY() + dy, z = base.getBlockZ() + dz;
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    Block b = w.getBlockAt(x, y, z);
                    if (!match.test(b.getType().name()) || sv.blacklist.containsKey(key(b))) continue;
                    found.add(new Cand(b, dx * dx + dy * dy + dz * dz));
                }
        found.sort((a, b) -> Double.compare(a.d2(), b.d2()));
        int checked = 0;
        for (Cand c : found) {
            if (checked++ >= 8) break; // policy checks are expensive: only the nearest few
            Location stand = findStand(c.b(), cfg);
            if (stand == null) { ban(sv, c.b()); continue; }
            BlockPolicy.Verdict v = protection.canBreak(c.b());
            if (!v.allowed()) { ban(sv, c.b()); Debug.log(bot.name() + " skips " + c.b().getType().name() + ": " + v.reason()); continue; }
            sv.stand = stand;
            return c.b();
        }
        return null;
    }

    /** A standable spot beside (or just below) the block, from which its centre is within reach. */
    private Location findStand(Block b, Settings.Survival cfg) {
        BukkitTerrainProbe probe = new BukkitTerrainProbe(b.getWorld());
        for (int[] o : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
            for (int dy : new int[] {0, -1}) {
                int x = b.getX() + o[0], y = b.getY() + dy, z = b.getZ() + o[1];
                if (!Pathfinder.standable(probe, x, y, z)) continue;
                double dxx = b.getX() + 0.5 - (x + 0.5), dyy = b.getY() + 0.5 - (y + 1.62), dzz = b.getZ() + 0.5 - (z + 0.5);
                if (Math.sqrt(dxx * dxx + dyy * dyy + dzz * dzz) <= cfg.reach() - 0.3) return new Location(b.getWorld(), x + 0.5, y, z + 0.5);
            }
        return null;
    }

    private void ban(SV sv, Block b) { sv.blacklist.put(key(b), tick + 20L * 120); }

    private static String key(Block b) { return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ(); }

    private boolean tableNearby(LivingEntity e, int r) {
        World w = e.getWorld();
        Location l = e.getLocation();
        for (int dx = -r; dx <= r; dx++)
            for (int dz = -r; dz <= r; dz++)
                for (int dy = -2; dy <= 2; dy++) {
                    int x = l.getBlockX() + dx, z = l.getBlockZ() + dz;
                    if (w.isChunkLoaded(x >> 4, z >> 4) && w.getBlockAt(x, l.getBlockY() + dy, z).getType() == Material.CRAFTING_TABLE) return true;
                }
        return false;
    }

    // ------------------------------------------------------------------ inventory

    private static Map<String, Integer> counts(BotInventory inv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < BotInventory.MAIN_SLOTS; i++) {
            ItemStack s = inv.get(i);
            if (s != null) m.merge(s.getType().name(), s.getAmount(), Integer::sum);
        }
        return m;
    }

    private static List<String> unionKeys(Map<String, Integer> a, Map<String, Integer> b) {
        TreeMap<String, Integer> u = new TreeMap<>(a);
        b.keySet().forEach(k -> u.putIfAbsent(k, 0));
        return new ArrayList<>(u.keySet());
    }

    /** Drops surplus junk (never valuables) in the world to free slots. Returns true if at least one slot was freed. */
    private boolean makeRoom(BotIdentity bot, LivingEntity e) {
        BotInventory inv = bot.inventory();
        Map<String, Integer> junk = InventoryPlanner.surplus(counts(inv));
        if (junk.isEmpty()) return inv.freeSlots() > 0;
        for (Map.Entry<String, Integer> j : junk.entrySet()) {
            Material m = Material.valueOf(j.getKey());
            if (inv.remove(m, j.getValue())) e.getWorld().dropItemNaturally(e.getLocation(), new ItemStack(m, j.getValue()));
        }
        bot.markDirty();
        return inv.freeSlots() > 0;
    }
}
