package net.mtree.livingplayers.entity;

import net.kyori.adventure.text.Component;
import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotInventory;
import net.mtree.livingplayers.bot.BotLocation;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Renders bots as vanilla {@link Mannequin} entities (Paper API, verified against the 1.21.11 Javadoc).
 *
 * <p>What a Mannequin gives us: player-shaped model, skin via ResolvableProfile, equipment rendering,
 * a LivingEntity with health/damage/death. What it does NOT give us: pathfinding, a real inventory, hunger,
 * player events. Those are provided by the plugin (BotInventory, MovementController in a later stage).
 *
 * <p>Entities are marked non-persistent so they are never saved into chunk files; the database is the single
 * source of truth. Every entity carries a PDC tag so orphans can be found and removed safely.
 */
public final class MannequinAdapter implements BotEntityAdapter {
    private static final EquipmentSlot[] RENDER_SLOTS = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND};

    private final Logger log;
    private final NamespacedKey botKey;
    private final SkinService skins;
    private final Supplier<Settings> settings;
    private final Map<UUID, Mannequin> live = new HashMap<>(); // main thread only

    public MannequinAdapter(Plugin plugin, SkinService skins, Supplier<Settings> settings) {
        this.log = plugin.getLogger();
        this.botKey = new NamespacedKey(plugin, "bot_id");
        this.skins = skins;
        this.settings = settings;
    }

    @Override public String representation() { return "mannequin"; }

    private static void requireMain() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("BotEntityAdapter must be used from the main thread");
    }

    @Override
    public SpawnOutcome spawn(BotIdentity bot, Location at) {
        requireMain();
        Mannequin existing = live.get(bot.id());
        if (existing != null && existing.isValid()) return SpawnOutcome.fail("bot already has a live entity");
        live.remove(bot.id());
        World w = at.getWorld();
        if (w == null) return SpawnOutcome.fail("no world");
        try {
            Mannequin m = w.spawn(at, Mannequin.class, e -> configure(e, bot));
            live.put(bot.id(), m);
            skins.apply(m, bot);
            return SpawnOutcome.ok(m);
        } catch (RuntimeException ex) {
            log.log(Level.WARNING, "Failed to spawn mannequin for " + bot.name(), ex);
            return SpawnOutcome.fail("spawn threw " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    @SuppressWarnings("deprecation") // Damageable#getMaxHealth: avoids depending on Attribute constant names across versions
    private void configure(Mannequin m, BotIdentity bot) {
        Settings.BotsCfg cfg = settings.get().bots();
        m.getPersistentDataContainer().set(botKey, PersistentDataType.STRING, bot.id().toString());
        m.setPersistent(false);
        m.setRemoveWhenFarAway(false);
        m.setCanPickupItems(false);
        m.setImmovable(false);
        m.setInvulnerable(false);
        m.customName(Component.text(bot.name()));
        m.setCustomNameVisible(cfg.showNameTag());
        m.setDescription(cfg.description().isBlank() ? null : Component.text(cfg.description()));
        m.setHealth(Math.max(0.5, Math.min(bot.health(), m.getMaxHealth())));
        applyEquipment(m, bot.inventory());
    }

    private void applyEquipment(Mannequin m, BotInventory inv) {
        EntityEquipment eq = m.getEquipment();
        eq.setBoots(copy(inv.armor(0)));
        eq.setLeggings(copy(inv.armor(1)));
        eq.setChestplate(copy(inv.armor(2)));
        eq.setHelmet(copy(inv.armor(3)));
        eq.setItemInMainHand(copy(inv.held()));
        eq.setItemInOffHand(copy(inv.offhand()));
    }

    private static ItemStack copy(ItemStack s) { return BotInventory.isEmpty(s) ? null : s.clone(); }

    @Override
    public void despawn(UUID botId) {
        requireMain();
        Mannequin m = live.remove(botId);
        if (m != null && m.isValid()) m.remove();
    }

    @Override public void forget(UUID botId) { live.remove(botId); }

    @Override
    public Optional<LivingEntity> entity(UUID botId) {
        Mannequin m = live.get(botId);
        return m != null && m.isValid() ? Optional.of(m) : Optional.empty();
    }

    @Override
    public Optional<UUID> botIdOf(Entity entity) {
        if (!(entity instanceof Mannequin)) return Optional.empty();
        String raw = entity.getPersistentDataContainer().get(botKey, PersistentDataType.STRING);
        if (raw == null) return Optional.empty();
        try { return Optional.of(UUID.fromString(raw)); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    @Override public Collection<UUID> trackedBotIds() { return List.copyOf(live.keySet()); }

    @Override
    public void pullState(BotIdentity bot) {
        Mannequin m = live.get(bot.id());
        if (m == null || !m.isValid()) return;
        Location l = m.getLocation();
        if (l.getWorld() != null)
            bot.setLastLocation(new BotLocation(l.getWorld().getUID(), l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch()));
        bot.setHealth(m.getHealth());
        EntityEquipment eq = m.getEquipment();
        BotInventory inv = bot.inventory();
        inv.setArmor(0, eq.getBoots());
        inv.setArmor(1, eq.getLeggings());
        inv.setArmor(2, eq.getChestplate());
        inv.setArmor(3, eq.getHelmet());
        inv.setOffhand(eq.getItemInOffHand());
        ItemStack hand = eq.getItemInMainHand();
        ItemStack held = inv.held();
        // only write back wear/damage on the same item; never let the render replace what the bot owns
        if (!BotInventory.isEmpty(hand) && held != null && held.getType() == hand.getType()) inv.setHeld(hand);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void pushState(BotIdentity bot) {
        Mannequin m = live.get(bot.id());
        if (m == null || !m.isValid()) return;
        applyEquipment(m, bot.inventory());
        m.setHealth(Math.max(0.5, Math.min(bot.health(), m.getMaxHealth())));
    }

    @Override
    public int sweepOrphans(Collection<World> worlds) {
        requireMain();
        int removed = 0;
        for (World w : worlds)
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class))
                if (removeIfOrphan(m)) removed++;
        return removed;
    }

    @Override
    public boolean removeIfOrphan(Entity entity) {
        Optional<UUID> id = botIdOf(entity);
        if (id.isEmpty()) return false;
        Mannequin registered = live.get(id.get());
        if (registered != null && registered.getUniqueId().equals(entity.getUniqueId())) return false;
        entity.remove();
        Debug.log("Removed orphaned bot entity for bot " + id.get());
        return true;
    }
}
