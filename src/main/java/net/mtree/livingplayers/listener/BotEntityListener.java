package net.mtree.livingplayers.listener;

import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.combat.CombatAI;
import net.mtree.livingplayers.social.SocialService;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Bot deaths are handled here and ONLY here: bots are Mannequins, not Players, so PlayerDeathEvent never fires
 * for them. That is what keeps player-grave / death-penalty plugins from seeing a bot death. (Plugins that listen to
 * EntityDeathEvent for every LivingEntity - e.g. head-drop plugins - can still react; see README.)
 */
public final class BotEntityListener implements Listener {
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final Supplier<Settings> settings;
    private final CombatAI combat;
    private final SocialService social;

    public BotEntityListener(BotManager manager, BotEntityAdapter adapter, Supplier<Settings> settings, CombatAI combat, SocialService social) {
        this.manager = manager; this.adapter = adapter; this.settings = settings; this.combat = combat; this.social = social;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent e) {
        Optional<UUID> id = adapter.botIdOf(e.getEntity());
        if (id.isEmpty()) return;
        manager.byId(id.get()).ifPresentOrElse(
            bot -> {
                org.bukkit.Location where = e.getEntity().getLocation();
                org.bukkit.entity.Player killer = e.getEntity().getKiller();
                manager.handleDeath(bot, e.getEntity(), e);
                social.onBotDied(bot, killer, where);
            },
            () -> { e.getDrops().clear(); e.setDroppedExp(0); }); // orphan: never produces loot
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (settings.get().death().playersCanDamageBots()) return;
        if (adapter.botIdOf(e.getEntity()).isEmpty()) return;
        Entity damager = e.getDamager();
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) damager = shooter;
        if (damager instanceof Player) e.setCancelled(true);
    }

    /** Remembers who hurt a bot (after other plugins had their say) so it can fight back. */
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBotHurt(EntityDamageByEntityEvent e) {
        Optional<UUID> id = adapter.botIdOf(e.getEntity());
        if (id.isEmpty()) return;
        Entity damager = e.getDamager();
        if (damager instanceof Projectile p && p.getShooter() instanceof Entity shooter) damager = shooter;
        combat.noteDamaged(id.get(), damager);
        social.onBotHurt(id.get(), damager);
    }

    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent e) {
        for (Entity en : new ArrayList<>(e.getEntities())) adapter.removeIfOrphan(en);
    }

    @EventHandler
    public void onEntitiesUnload(EntitiesUnloadEvent e) {
        for (Entity en : new ArrayList<>(e.getEntities())) {
            Optional<UUID> id = adapter.botIdOf(en);
            if (id.isEmpty()) continue;
            boolean registered = adapter.entity(id.get()).map(x -> x.getUniqueId().equals(en.getUniqueId())).orElse(false);
            if (registered) manager.onEntityUnloading(id.get());
        }
    }
}
