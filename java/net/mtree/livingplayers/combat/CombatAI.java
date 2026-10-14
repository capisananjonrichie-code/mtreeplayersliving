package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotInventory;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.combat.CombatBrain.Action;
import net.mtree.livingplayers.combat.TargetSelector.Candidate;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.director.BukkitTerrainProbe;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.nav.BotNavigator;
import net.mtree.livingplayers.nav.Pathfinder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Melee combat for ACTIVE bots. All gameplay numbers come from the pure classes in this package; this class only reads
 * the world and applies results. Rules it keeps:
 * <ul>
 *   <li>A bot hits with what it OWNS (fists if nothing), at vanilla damage scaled by the attack cooldown.</li>
 *   <li>It must be within reach, have line of sight and be facing the target (turn rate and tolerance by skill).</li>
 *   <li>Reaction delay, mistakes and turn limits apply at every skill level; there is no instant tracking.</li>
 *   <li>Real players are only fought in retaliation (config). Healing/eating consumes real items and hunger.</li>
 * </ul>
 * UNVERIFIED ON A LIVE SERVER: that damage(amount, mannequin) knocks back and triggers hurt animations as for mobs;
 * see docs/TEST_PLAN.md (T3.x).
 */
public final class CombatAI {
    private static final class St {
        UUID target;
        long lastAttackTick = -100, reactAt, nextScan, nextPathTick, strafeUntil, eatUntil, nextUpkeep, jumpedAt = -100;
        int strafeDir = 1, eatSlot = -1, threats;
        boolean retreating, jumpCrit;
        String eatMat;
        final Map<UUID, Long> attackers = new HashMap<>();
        String note = "";
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final BotNavigator navigator;
    private final BotEquipmentService equipment;
    private final BotVitalsService vitals;
    private final Map<UUID, St> states = new HashMap<>();
    private final Random rnd = new Random();
    private BukkitTask task;
    private long tick;

    public CombatAI(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter,
                    BotNavigator navigator, BotEquipmentService equipment, BotVitalsService vitals) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter;
        this.navigator = navigator; this.equipment = equipment; this.vitals = vitals;
    }

    public void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }
    public void stop() { if (task != null) task.cancel(); task = null; }

    /** Called by the listener when something damages a bot. Records the attacker and forces an immediate rescan. */
    public void noteDamaged(UUID botId, Entity damager) {
        if (damager == null) return;
        St st = states.computeIfAbsent(botId, k -> new St());
        st.attackers.put(damager.getUniqueId(), tick);
        st.nextScan = 0;
    }

    /** True while the bot has a target, is retreating or is eating: other AI must not take over its movement. */
    public boolean inCombat(UUID id) {
        St st = states.get(id);
        return st != null && (st.target != null || st.retreating || st.eatUntil > tick);
    }

    public boolean isRetreating(UUID id) { St st = states.get(id); return st != null && st.retreating; }
    public boolean isEating(UUID id) { St st = states.get(id); return st != null && st.eatUntil > tick; }

    public String describe(UUID id) {
        St st = states.get(id);
        if (st == null) return "combat: idle";
        return "combat: target=" + (st.target == null ? "none" : st.target) + " threats=" + st.threats + (st.retreating ? " RETREATING" : "")
            + (st.eatUntil > tick ? " eating" : "") + (st.note.isEmpty() ? "" : " | " + st.note);
    }

    // ------------------------------------------------------------------ tick

    private void tick() {
        Settings.Combat cfg = settings.get().combat();
        if (!manager.isReady() || !cfg.enabled()) return;
        tick++;
        states.keySet().removeIf(id -> manager.byId(id).map(b -> b.state() != LifecycleState.ACTIVE).orElse(true));
        for (BotIdentity bot : manager.all()) {
            if (bot.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> oe = adapter.entity(bot.id());
            if (oe.isEmpty()) continue;
            try {
                handle(bot, oe.get(), states.computeIfAbsent(bot.id(), k -> new St()), cfg);
            } catch (RuntimeException ex) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "Combat tick failed for " + bot.name(), ex);
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void handle(BotIdentity bot, LivingEntity e, St st, Settings.Combat cfg) {
        SkillProfile prof = cfg.profile(bot.skill());
        double maxHp = e.getMaxHealth();
        double hpFrac = maxHp <= 0 ? 1 : e.getHealth() / maxHp;
        if (st.retreating && hpFrac >= prof.reengageAbove()) st.retreating = false;

        if (st.eatUntil > 0) {
            if (tick < st.eatUntil) { navigator.halt(bot.id()); return; }
            finishEating(bot, st);
        }

        if (st.target == null || tick >= st.nextScan) { scan(bot, e, st, prof, cfg); st.nextScan = tick + cfg.scanIntervalTicks(); }
        LivingEntity target = resolve(st.target, e.getWorld());
        if (target == null) {
            st.target = null;
            navigator.setCombatControl(bot.id(), false);
            upkeep(bot, e, st, hpFrac);
            return;
        }

        navigator.setCombatControl(bot.id(), true);
        if (tick < st.reactAt) return; // reaction delay: it noticed, but has not reacted yet

        Location me = e.getLocation(), tl = target.getLocation();
        double dx = tl.getX() - me.getX(), dz = tl.getZ() - me.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz + Math.pow(tl.getY() - me.getY(), 2));
        boolean los = e.hasLineOfSight(target);

        ItemStack held = bot.inventory().held();
        WeaponStats w = WeaponStats.of(held == null ? null : held.getType().name());
        double charge = AttackCooldown.charge(tick - st.lastAttackTick, w.attackSpeed());
        int idx = FoodTable.bestFoodIndex(bot.inventory().names(), bot.hunger());
        boolean hasFood = idx >= 0 && vitals.canEatNow(bot);

        CombatBrain.Situation sit = new CombatBrain.Situation(hpFrac, hasFood, st.retreating, dist, cfg.reach(), charge, Math.max(1, st.threats), prof.awarenessRange());
        Action a = CombatBrain.decide(sit, prof, bot.style(), rnd);
        st.note = a.name() + " dist=" + String.format("%.1f", dist) + " charge=" + String.format("%.2f", charge) + " los=" + los;

        switch (a) {
            case ATTACK -> { navigator.halt(bot.id()); attack(bot, e, target, st, prof, cfg, w, charge, dx, dz, los, dist); }
            case APPROACH -> approach(bot, e, target, st, prof, cfg, dx, dz, dist, los);
            case STRAFE -> { navigator.halt(bot.id()); strafe(e, st, prof, dx, dz); }
            case EAT -> { st.retreating = st.retreating || hpFrac <= prof.retreatBelow(); startEating(bot, e, st, idx); }
            case RETREAT -> { st.retreating = true; retreat(bot, e, target, st); }
            case DISENGAGE -> { st.target = null; navigator.halt(bot.id()); navigator.setCombatControl(bot.id(), false); }
        }
    }

    // ------------------------------------------------------------------ targeting

    @SuppressWarnings("deprecation")
    private void scan(BotIdentity bot, LivingEntity e, St st, SkillProfile prof, Settings.Combat cfg) {
        double r = prof.awarenessRange();
        long window = cfg.retaliationWindowSeconds() * 20L;
        List<Candidate> cands = new ArrayList<>();
        for (Entity n : e.getNearbyEntities(r, Math.min(r, 8), r)) {
            if (!(n instanceof LivingEntity le) || le.isDead() || !le.isValid()) continue;
            if (adapter.botIdOf(n).isPresent()) continue; // bot-vs-bot fights belong to a later stage
            boolean player = n instanceof Player;
            if (player) {
                GameMode gm = ((Player) n).getGameMode();
                if (gm == GameMode.CREATIVE || gm == GameMode.SPECTATOR) continue;
            }
            boolean hostile = n instanceof Monster;
            Long at = st.attackers.get(n.getUniqueId());
            boolean attacked = at != null && tick - at <= window;
            if (!player && !hostile && !attacked) continue;
            double mh = le.getMaxHealth();
            cands.add(new Candidate(n.getUniqueId(), e.getLocation().distance(n.getLocation()), player, hostile, attacked,
                e.hasLineOfSight(le), mh <= 0 ? 1 : le.getHealth() / mh, false));
        }
        st.attackers.values().removeIf(t -> tick - t > window);
        st.threats = TargetSelector.countThreats(cands, r, cfg.attackPlayers());
        Optional<Candidate> sel = TargetSelector.select(cands, r, cfg.attackPlayers(), st.target);
        if (sel.isPresent()) {
            if (!sel.get().id().equals(st.target)) {
                boolean first = st.target == null;
                st.target = sel.get().id();
                if (first) { st.reactAt = tick + prof.reactionTicks(); equipment.refresh(bot); }
                Debug.log(bot.name() + " targets " + st.target);
            }
        } else {
            st.target = null;
        }
    }

    private LivingEntity resolve(UUID id, World world) {
        if (id == null) return null;
        Entity en = Bukkit.getEntity(id);
        return en instanceof LivingEntity le && le.isValid() && !le.isDead() && le.getWorld().equals(world) ? le : null;
    }

    // ------------------------------------------------------------------ actions

    private void attack(BotIdentity bot, LivingEntity e, LivingEntity target, St st, SkillProfile prof, Settings.Combat cfg,
                        WeaponStats w, double charge, double dx, double dz, boolean los, double dist) {
        double err = turn(e, dx, dz, prof.turnRateDeg());
        boolean inReach = dist <= cfg.reach() && Math.abs(target.getLocation().getY() - e.getLocation().getY()) <= 2.5;
        if (!inReach || !los || err > prof.facingToleranceDeg()) { steer(e, dx, dz, 0.2); return; } // keep closing / keep turning

        Vector vel = e.getVelocity();
        boolean sprinting = Math.hypot(vel.getX(), vel.getZ()) > 0.22;
        boolean airborne = !e.isOnGround();
        boolean falling = airborne && vel.getY() < 0 && e.getFallDistance() > 0;

        // crit attempt: hop first, strike on the way down (sprinting and water cancel crits, as in vanilla)
        if (!st.jumpCrit && e.isOnGround() && rnd.nextDouble() < prof.critChance() * 0.1 && charge > 0.7) {
            vel.setY(0.42); vel.setX(vel.getX() * 0.3); vel.setZ(vel.getZ() * 0.3); e.setVelocity(vel);
            st.jumpCrit = true; st.jumpedAt = tick;
            return;
        }
        if (st.jumpCrit) {
            if (e.isOnGround() && tick - st.jumpedAt > 3) st.jumpCrit = false;      // landed without striking
            else if (!falling && tick - st.jumpedAt < 14) return;                   // still rising
            else st.jumpCrit = false;
        }
        boolean crit = AttackCooldown.canCrit(charge, falling, e.isOnGround(), sprinting, e.isInWater());
        double dmg = AttackCooldown.damage(w.damage(), charge, crit);
        e.swingMainHand();
        target.damage(dmg, e);
        st.lastAttackTick = tick;
        vitals.addExhaustion(bot.id(), 0.1f);
        if (cfg.weaponDurability() && w.isWeapon()) wear(bot);
        Debug.log(String.format("%s hits for %.1f%s with %s", bot.name(), dmg, crit ? " (crit)" : "", w.kind()));
    }

    private void wear(BotIdentity bot) {
        BotInventory inv = bot.inventory();
        ItemStack held = inv.held();
        if (held == null) return;
        ItemMeta meta = held.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable d)) return;
        int next = d.getDamage() + 1;
        if (next >= held.getType().getMaxDurability()) {
            inv.setHeld(null); // it breaks
            Debug.log(bot.name() + "'s weapon broke");
            equipment.refresh(bot);
        } else {
            d.setDamage(next);
            held.setItemMeta(d);
        }
        bot.markDirty();
        adapter.pushState(bot);
    }

    private void approach(BotIdentity bot, LivingEntity e, LivingEntity target, St st, SkillProfile prof, Settings.Combat cfg,
                          double dx, double dz, double dist, boolean los) {
        turn(e, dx, dz, prof.turnRateDeg());
        if (dist <= cfg.reach() * 0.8) { navigator.halt(bot.id()); return; } // close enough: hold ground while the weapon recharges
        if (dist <= 7 && los) { navigator.halt(bot.id()); steer(e, dx, dz, 0.26); return; } // short, clear run: sprint straight in
        if (tick >= st.nextPathTick) { navigator.pursue(bot, target.getLocation(), true, Math.max(1.0, cfg.reach() - 0.5)); st.nextPathTick = tick + 10; }
    }

    private void strafe(LivingEntity e, St st, SkillProfile prof, double dx, double dz) {
        turn(e, dx, dz, prof.turnRateDeg());
        if (tick >= st.strafeUntil) { st.strafeDir = rnd.nextBoolean() ? 1 : -1; st.strafeUntil = tick + 10 + rnd.nextInt(20); }
        double len = Math.max(Math.hypot(dx, dz), 1e-6);
        steer(e, -dz / len * st.strafeDir + dx / len * 0.15, dx / len * st.strafeDir + dz / len * 0.15, 0.15);
    }

    private void retreat(BotIdentity bot, LivingEntity e, LivingEntity from, St st) {
        if (tick < st.nextPathTick) return;
        st.nextPathTick = tick + 20;
        Location spot = fleeSpot(e, from.getLocation());
        if (spot != null) navigator.pursue(bot, spot, true, 1.5);
        else { Location me = e.getLocation(); steer(e, me.getX() - from.getLocation().getX(), me.getZ() - from.getLocation().getZ(), 0.26); }
    }

    private Location fleeSpot(LivingEntity e, Location threat) {
        Location me = e.getLocation();
        double ax = me.getX() - threat.getX(), az = me.getZ() - threat.getZ();
        double len = Math.max(Math.hypot(ax, az), 1e-6);
        ax /= len; az /= len;
        BukkitTerrainProbe probe = new BukkitTerrainProbe(e.getWorld());
        for (double deg : new double[] {0, 30, -30, 60, -60}) {
            double rad = Math.toRadians(deg), c = Math.cos(rad), s = Math.sin(rad);
            int x = (int) Math.floor(me.getX() + (ax * c - az * s) * 14), z = (int) Math.floor(me.getZ() + (ax * s + az * c) * 14);
            for (int dy : new int[] {0, 1, -1, 2, -2, 3, -3})
                if (Pathfinder.standable(probe, x, me.getBlockY() + dy, z)) return new Location(e.getWorld(), x + 0.5, me.getBlockY() + dy, z + 0.5);
        }
        return null;
    }

    // ------------------------------------------------------------------ eating

    private void startEating(BotIdentity bot, LivingEntity e, St st, int idx) {
        if (idx < 0 || !vitals.canEatNow(bot)) { st.retreating = true; return; }
        st.eatSlot = idx;
        st.eatMat = bot.inventory().names().get(idx);
        st.eatUntil = tick + 32; // vanilla: 1.6 s
        bot.inventory().select(idx);
        adapter.pushState(bot);
        navigator.halt(bot.id());
    }

    private void finishEating(BotIdentity bot, St st) {
        st.eatUntil = 0;
        Optional<FoodTable.Food> food = st.eatMat == null ? Optional.empty() : FoodTable.of(st.eatMat);
        org.bukkit.Material m = null;
        ItemStack in = st.eatSlot >= 0 ? bot.inventory().get(st.eatSlot) : null;
        if (in != null && food.isPresent() && in.getType().name().equals(st.eatMat)) m = in.getType();
        if (m != null && bot.inventory().remove(m, 1)) {   // the item is really consumed; no food, no healing
            vitals.eat(bot, food.get());
            bot.markDirty();
            Debug.log(bot.name() + " ate " + st.eatMat);
        }
        st.eatSlot = -1; st.eatMat = null;
        equipment.refresh(bot);
        adapter.pushState(bot);
    }

    /** No enemy around: eat when hungry or wounded, and keep gear sorted. */
    @SuppressWarnings("deprecation")
    private void upkeep(BotIdentity bot, LivingEntity e, St st, double hpFrac) {
        if (tick < st.nextUpkeep) return;
        st.nextUpkeep = tick + 40;
        int idx = FoodTable.bestFoodIndex(bot.inventory().names(), bot.hunger());
        boolean wounded = hpFrac < 0.7 || st.retreating;
        if (idx >= 0 && vitals.canEatNow(bot) && (bot.hunger() <= 14 || wounded)) { startEating(bot, e, st, idx); return; }
        if (!navigator.isBusy(bot.id())) equipment.refresh(bot); // never swap away from a tool a gathering task is using
    }

    // ------------------------------------------------------------------ movement helpers

    /** Turns toward (dx,dz) by at most maxTurn degrees; returns the remaining angular error in degrees. */
    private static double turn(LivingEntity e, double dx, double dz, double maxTurn) {
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float cur = e.getLocation().getYaw();
        float delta = ((want - cur + 540f) % 360f) - 180f;
        float step = (float) Math.max(-maxTurn, Math.min(maxTurn, delta));
        e.setRotation(cur + step, 0f);
        return Math.abs(delta - step);
    }

    /** Direct velocity toward a direction (used for short, line-of-sight moves). Hops up 1-block obstacles. */
    private static void steer(LivingEntity e, double dx, double dz, double speed) {
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return;
        double nx = dx / len, nz = dz / len;
        Vector v = e.getVelocity();
        v.setX(nx * speed);
        v.setZ(nz * speed);
        Location l = e.getLocation();
        int bx = (int) Math.floor(l.getX() + nx * 0.8), bz = (int) Math.floor(l.getZ() + nz * 0.8), by = l.getBlockY();
        World w = e.getWorld();
        if (e.isOnGround() && !w.getBlockAt(bx, by, bz).isPassable() && w.getBlockAt(bx, by + 1, bz).isPassable() && w.getBlockAt(bx, by + 2, bz).isPassable())
            v.setY(0.42);
        e.setVelocity(v);
    }
}
