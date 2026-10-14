package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Runs {@link HungerModel} for ACTIVE bots (one step per tick). Health lives on the entity; hunger/saturation live on the
 * identity (persisted). Regeneration therefore always costs food and starvation is real.
 */
public final class BotVitalsService {
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final Map<UUID, HungerModel.State> states = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();
    private BukkitTask task;
    private long tick;

    public BotVitalsService(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter;
    }

    public void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L); }
    public void stop() { if (task != null) task.cancel(); task = null; }

    public boolean canEatNow(BotIdentity b) { return b.hunger() < 20; }

    public void addExhaustion(UUID id, float amount) {
        HungerModel.State s = states.get(id);
        if (s != null) states.put(id, HungerModel.addExhaustion(s, amount));
    }

    public void eat(BotIdentity bot, FoodTable.Food food) {
        HungerModel.State s = states.getOrDefault(bot.id(), HungerModel.State.of(bot.hunger(), bot.saturation()));
        s = HungerModel.eat(s, food.nutrition(), food.saturation());
        states.put(bot.id(), s);
        bot.setHunger(s.hunger());
        bot.setSaturation(s.saturation());
    }

    @SuppressWarnings("deprecation")
    private void tick() {
        if (!manager.isReady() || !settings.get().combat().vitalsEnabled()) return;
        tick++;
        states.keySet().removeIf(id -> manager.byId(id).map(b -> b.state() != LifecycleState.ACTIVE).orElse(true));
        lastPos.keySet().removeIf(id -> !states.containsKey(id));

        for (BotIdentity bot : manager.all()) {
            if (bot.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> oe = adapter.entity(bot.id());
            if (oe.isEmpty()) continue;
            LivingEntity e = oe.get();
            HungerModel.State st = states.computeIfAbsent(bot.id(), k -> HungerModel.State.of(bot.hunger(), bot.saturation()));

            if (tick % 20 == 0) { // sprint-speed travel costs exhaustion (inferred from distance moved in the last second)
                Location now = e.getLocation(), prev = lastPos.put(bot.id(), now);
                if (prev != null && prev.getWorld() != null && prev.getWorld().equals(now.getWorld())) {
                    double moved = Math.hypot(now.getX() - prev.getX(), now.getZ() - prev.getZ());
                    if (moved > 4.8) st = HungerModel.addExhaustion(st, (float) (moved * 0.1));
                }
            }

            double hp = e.getHealth(), max = e.getMaxHealth();
            HungerModel.Result r = HungerModel.tick(st, hp, max, e.getWorld().getDifficulty() == Difficulty.HARD);
            states.put(bot.id(), r.state());
            if (r.state().hunger() != bot.hunger()) bot.setHunger(r.state().hunger());
            bot.setSaturation(r.state().saturation());
            if (r.healthDelta() > 0) e.setHealth(Math.min(max, hp + r.healthDelta()));
            else if (r.healthDelta() < 0) e.damage(-r.healthDelta());
        }
    }
}
