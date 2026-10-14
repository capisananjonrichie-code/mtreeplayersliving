package net.mtree.livingplayers.survival;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotInventory;
import net.mtree.livingplayers.combat.BotVitalsService;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.protect.BlockPolicy;
import net.mtree.livingplayers.protect.ProtectionService;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Breaks and places blocks on behalf of bots, through the same rules a player would face:
 * policy check (twice for breaks), reach, line of sight, vanilla break time for the tool in hand, real drops from
 * Block#getDrops(tool) moved into the bot's inventory (overflow is dropped in the world, never deleted or duplicated),
 * tool durability, and consumption of the placed item. Main thread.
 *
 * <p>Limitation: no BlockBreakEvent/BlockPlaceEvent is fired (bots are not Players), so block-logging plugins such as
 * CoreProtect will not see these changes.
 */
public final class BlockInteractionService {
    public enum Status { IN_PROGRESS, DONE, DENIED, INVALID, OUT_OF_REACH, NO_LINE_OF_SIGHT, NO_SPACE }

    public record Outcome(Status status, String detail) {
        static Outcome of(Status s, String d) { return new Outcome(s, d); }
    }

    private static final class Prog {
        final Block block; final String material; int ticks; int needed; int toolSlot = -1; String toolName;
        Prog(Block b) { block = b; material = b.getType().name(); }
    }

    private final Supplier<Settings> settings;
    private final BotEntityAdapter adapter;
    private final ProtectionService protection;
    private final BotPlacedBlocks placed;
    private final BotVitalsService vitals;
    private final Map<UUID, Prog> progress = new HashMap<>();

    public BlockInteractionService(Supplier<Settings> settings, BotEntityAdapter adapter, ProtectionService protection,
                                   BotPlacedBlocks placed, BotVitalsService vitals) {
        this.settings = settings; this.adapter = adapter; this.protection = protection; this.placed = placed; this.vitals = vitals;
    }

    public void cancel(UUID botId) { progress.remove(botId); }

    public boolean inReachAndSight(LivingEntity e, Block target) {
        return reachProblem(e, target) == null;
    }

    private Outcome reachProblem(LivingEntity e, Block target) {
        Location eye = e.getEyeLocation();
        if (!eye.getWorld().equals(target.getWorld())) return Outcome.of(Status.INVALID, "different world");
        Location c = target.getLocation().add(0.5, 0.5, 0.5);
        double dist = eye.distance(c);
        if (dist > settings.get().survival().reach()) return Outcome.of(Status.OUT_OF_REACH, "too far (" + String.format("%.1f", dist) + ")");
        Vector dir = c.toVector().subtract(eye.toVector()).normalize();
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir, dist + 0.5);
        if (hit == null || hit.getHitBlock() == null || !hit.getHitBlock().equals(target)) return Outcome.of(Status.NO_LINE_OF_SIGHT, "something is in the way");
        return null;
    }

    /** Call once per tick while the bot is mining {@code target}. */
    public Outcome mine(BotIdentity bot, LivingEntity e, Block target) {
        Outcome problem = reachProblem(e, target);
        if (problem != null) return problem;
        BotInventory inv = bot.inventory();
        if (inv.freeSlots() < 1) return Outcome.of(Status.NO_SPACE, "inventory full");

        Prog p = progress.get(bot.id());
        if (p == null || !p.block.equals(target) || !target.getType().name().equals(p.material)) {
            BlockPolicy.Verdict v = protection.canBreak(target);
            if (!v.allowed()) return Outcome.of(Status.DENIED, v.reason());
            p = new Prog(target);
            p.toolSlot = BreakTime.bestToolIndex(inv.names(), p.material);
            p.toolName = p.toolSlot >= 0 ? inv.get(p.toolSlot).getType().name() : null;
            if (p.toolSlot >= 0) inv.select(p.toolSlot);
            adapter.pushState(bot);
            ItemStack held = inv.held();
            p.needed = BreakTime.ticks(p.material, p.toolSlot >= 0 ? p.toolName : (held == null ? null : held.getType().name()));
            progress.put(bot.id(), p);
        }
        if (p.toolSlot >= 0) {
            ItemStack at = inv.get(p.toolSlot);
            if (at == null || !at.getType().name().equals(p.toolName)) { progress.remove(bot.id()); return Outcome.of(Status.IN_PROGRESS, "tool changed, restarting"); }
            if (inv.selectedSlot() != p.toolSlot) { inv.select(p.toolSlot); adapter.pushState(bot); } // other AI may have swapped to a weapon
        }
        face(e, target);
        p.ticks++;
        if (p.ticks % 5 == 0) e.swingMainHand();
        if (p.ticks < p.needed) return Outcome.of(Status.IN_PROGRESS, p.ticks + "/" + p.needed);
        return finish(bot, e, p);
    }

    private Outcome finish(BotIdentity bot, LivingEntity e, Prog p) {
        progress.remove(bot.id());
        Block b = p.block;
        if (!b.getType().name().equals(p.material)) return Outcome.of(Status.INVALID, "block changed while mining");
        BlockPolicy.Verdict again = protection.canBreak(b); // surroundings may have changed (a player may have built here)
        if (!again.allowed()) return Outcome.of(Status.DENIED, again.reason());

        BotInventory inv = bot.inventory();
        ItemStack tool = inv.held();
        Collection<ItemStack> drops = b.getDrops(tool == null ? new ItemStack(Material.AIR) : tool.clone());
        boolean wasBotPlaced = placed.contains(b);
        Location where = b.getLocation().add(0.5, 0.5, 0.5);
        b.setType(Material.AIR);
        if (wasBotPlaced) placed.remove(b);
        for (ItemStack d : drops) {
            ItemStack left = inv.add(d);
            if (left != null) b.getWorld().dropItemNaturally(where, left); // conserved: it lands in the world instead
        }
        wear(bot, p.material);
        vitals.addExhaustion(bot.id(), 0.005f);
        bot.markDirty();
        adapter.pushState(bot);
        Debug.log(bot.name() + " mined " + p.material + " -> " + drops.size() + " drop stack(s)");
        return Outcome.of(Status.DONE, p.material);
    }

    private void wear(BotIdentity bot, String block) {
        BotInventory inv = bot.inventory();
        ItemStack held = inv.held();
        if (held == null || held.getType().getMaxDurability() <= 0) return;
        if (BreakTime.Tool.of(held.getType().name()).kind() == BreakTime.Kind.NONE) return;
        ItemMeta meta = held.getItemMeta();
        if (!(meta instanceof org.bukkit.inventory.meta.Damageable d)) return;
        int next = d.getDamage() + 1;
        if (next >= held.getType().getMaxDurability()) { inv.setHeld(null); Debug.log(bot.name() + "'s tool broke"); }
        else { d.setDamage(next); held.setItemMeta(d); }
    }

    private static void face(LivingEntity e, Block target) {
        Location eye = e.getEyeLocation();
        double dx = target.getX() + 0.5 - eye.getX(), dy = target.getY() + 0.5 - eye.getY(), dz = target.getZ() + 0.5 - eye.getZ();
        e.setRotation((float) Math.toDegrees(Math.atan2(-dx, dz)), (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))));
    }

    /** Reach/line-of-sight check for PLACING: the ray may end at an adjacent supporting block (as when a player clicks a face). */
    public Outcome placeReach(LivingEntity e, Block target) {
        Location eye = e.getEyeLocation();
        if (!eye.getWorld().equals(target.getWorld())) return Outcome.of(Status.INVALID, "different world");
        Location c = target.getLocation().add(0.5, 0.5, 0.5);
        double dist = eye.distance(c);
        if (dist > settings.get().survival().reach()) return Outcome.of(Status.OUT_OF_REACH, "too far");
        Vector dir = c.toVector().subtract(eye.toVector()).normalize();
        RayTraceResult hit = eye.getWorld().rayTraceBlocks(eye, dir, dist);
        if (hit != null && hit.getHitBlock() != null) {
            Block h = hit.getHitBlock();
            int manhattan = Math.abs(h.getX() - target.getX()) + Math.abs(h.getY() - target.getY()) + Math.abs(h.getZ() - target.getZ());
            if (!h.equals(target) && manhattan > 1) return Outcome.of(Status.NO_LINE_OF_SIGHT, "something is in the way");
        }
        return null;
    }

    /** Places one blueprint block from the bot's inventory. All checks happen here, every time. */
    public Outcome placeBuild(BotIdentity bot, LivingEntity e, Block at, Material mat, boolean insideProjectBox) {
        net.mtree.livingplayers.building.BuildPolicy.Verdict v = protection.canBuild(at, mat.name(), insideProjectBox);
        if (!v.allowed()) return Outcome.of(Status.DENIED, v.reason());
        Outcome reach = placeReach(e, at);
        if (reach != null) return reach;
        Location bl = e.getLocation();
        if (at.getX() == bl.getBlockX() && at.getZ() == bl.getBlockZ() && Math.abs(at.getY() - bl.getBlockY()) <= 1) return Outcome.of(Status.INVALID, "would be inside the bot");
        for (Entity other : at.getWorld().getNearbyEntities(at.getLocation().add(0.5, 0.5, 0.5), 0.6, 0.6, 0.6))
            if (!other.getUniqueId().equals(e.getUniqueId())) return Outcome.of(Status.INVALID, "an entity is in the way");
        if (!bot.inventory().remove(mat, 1)) return Outcome.of(Status.INVALID, "bot has no " + mat.name());
        at.setType(mat);
        placed.add(at, bot.id());
        e.swingMainHand();
        vitals.addExhaustion(bot.id(), 0.005f);
        bot.markDirty();
        return Outcome.of(Status.DONE, mat.name());
    }

    /** Places a crafting table from the bot's own inventory at {@code at}. */
    public Outcome placeTable(BotIdentity bot, LivingEntity e, Block at) {
        BlockPolicy.Verdict v = protection.canPlace(at);
        if (!v.allowed()) return Outcome.of(Status.DENIED, v.reason());
        if (!at.getType().isAir()) return Outcome.of(Status.INVALID, "target is not empty");
        Block below = at.getRelative(0, -1, 0);
        if (below.isPassable() || below.isLiquid()) return Outcome.of(Status.INVALID, "no solid support below");
        Location bl = e.getLocation();
        if (Math.hypot(bl.getX() - (at.getX() + 0.5), bl.getZ() - (at.getZ() + 0.5)) < 0.9) return Outcome.of(Status.INVALID, "would be inside the bot");
        for (Entity other : at.getWorld().getNearbyEntities(at.getLocation().add(0.5, 0.5, 0.5), 0.6, 0.6, 0.6))
            if (!other.getUniqueId().equals(e.getUniqueId())) return Outcome.of(Status.INVALID, "an entity is in the way");
        if (!bot.inventory().remove(Material.CRAFTING_TABLE, 1)) return Outcome.of(Status.INVALID, "bot has no crafting table");
        at.setType(Material.CRAFTING_TABLE);
        placed.add(at, bot.id());
        e.swingMainHand();
        bot.markDirty();
        adapter.pushState(bot);
        return Outcome.of(Status.DONE, "crafting table placed");
    }
}
