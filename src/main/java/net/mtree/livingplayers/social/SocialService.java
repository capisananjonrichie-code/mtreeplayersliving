package net.mtree.livingplayers.social;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.bot.Personality;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.chat.BotChat;
import net.mtree.livingplayers.chat.ActivitySummary;
import net.mtree.livingplayers.chat.DialogueEngine;
import net.mtree.livingplayers.chat.DialogueEngine.AmbientTopic;
import net.mtree.livingplayers.chat.DialogueEngine.Facts;
import net.mtree.livingplayers.chat.DialogueEngine.Style;
import net.mtree.livingplayers.combat.CombatAI;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.social.MemoryStore.Memory;
import net.mtree.livingplayers.storage.BotStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The social life of bots: remembers who did what, changes feelings, talks (rate-limited), shares rumors, forms factions and
 * helps faction mates. Everything it says goes through {@link BotChat}. Real players are never made enemies by this class:
 * it only changes how bots FEEL and SPEAK; combat decisions stay in CombatAI (which only fights players in retaliation).
 */
public final class SocialService {
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final CombatAI combat;
    private final BotChat chat;
    private final BotStorage storage;
    private final SocialState state;
    private final Random rnd = new Random();
    private final Map<String, Long> greeted = new HashMap<>();
    private final Map<UUID, Long> lastAmbient = new HashMap<>();
    private final Map<UUID, Long> nightSaid = new HashMap<>();
    private BukkitTask task;
    private long seconds;

    public SocialService(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter, CombatAI combat,
                         BotChat chat, BotStorage storage, SocialState state) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter; this.combat = combat;
        this.chat = chat; this.storage = storage; this.state = state;
    }

    public SocialState state() { return state; }

    public void start() { stop(); task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L); }
    public void stop() { if (task != null) task.cancel(); task = null; }

    /** Final synchronous-ish save on shutdown (called before storage closes). */
    public void saveNow() {
        try { storage.saveSocial(state.snapshot()).get(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception e) { plugin.getLogger().log(Level.SEVERE, "Final social save failed", e); }
    }

    // ------------------------------------------------------------------ helpers

    private Style styleOf(BotIdentity b) { return Style.of(b.personality(), b.id()); }

    private Stance stanceToward(BotIdentity b, UUID other) { return state.relationship(b.id(), other).stance(); }

    private Facts facts(BotIdentity b, String player) {
        return new Facts(player, b.name(), ActivitySummary.describe(combat.inCombat(b.id()), combat.isRetreating(b.id()), combat.isEating(b.id()), null, null));
    }

    private String nameOf(UUID id) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) return p.getName();
        return manager.byId(id).map(BotIdentity::name).orElse("someone");
    }

    private void ambient(BotIdentity b, AmbientTopic topic, UUID about) {
        Stance st = about == null ? Stance.NEUTRAL : stanceToward(b, about);
        chat.say(b, DialogueEngine.ambient(topic, st, styleOf(b), facts(b, about == null ? null : nameOf(about)), rnd), false);
    }

    private static boolean activeNear(Optional<LivingEntity> e, Location at, double r) {
        return e.isPresent() && e.get().getWorld().equals(at.getWorld()) && e.get().getLocation().distanceSquared(at) <= r * r;
    }

    // ------------------------------------------------------------------ event hooks

    /** Something hurt a bot. Real players become less liked; faction mates nearby come to help. */
    public void onBotHurt(UUID botId, Entity damager) {
        Optional<BotIdentity> ob = manager.byId(botId);
        if (ob.isEmpty() || damager == null) return;
        BotIdentity bot = ob.get();
        boolean player = damager instanceof Player;
        long now = System.currentTimeMillis();
        if (player) {
            state.record(botId, damager.getUniqueId(), true, SocialEvent.ATTACKED, bot.personality(), now);
            state.remember(botId, new Memory("ATTACKED_BY", damager.getUniqueId(), nameOf(damager.getUniqueId()) + " attacked me", 8, now));
            if (rnd.nextDouble() < 0.3) ambient(bot, AmbientTopic.WOUNDED, damager.getUniqueId());
        }
        Settings.Social cfg = settings.get().social();
        if (!cfg.factionsEnabled() || (player && !cfg.assistAgainstPlayers())) return;
        Optional<LivingEntity> victim = adapter.entity(botId);
        if (victim.isEmpty()) return;
        state.factions.of(botId).ifPresent(f -> {
            for (UUID mate : f.members()) {
                if (mate.equals(botId)) continue;
                Optional<LivingEntity> me = adapter.entity(mate);
                if (!activeNear(me, victim.get().getLocation(), cfg.assistRadius())) continue;
                combat.noteDamaged(mate, damager); // the mate treats the attacker as someone who attacked "us"
                manager.byId(mate).ifPresent(m -> state.remember(mate, new Memory("FRIEND_ATTACKED", botId, bot.name() + " was attacked", 6, now)));
            }
        });
    }

    /** A bot died. Its memory, its friends' grudges and the rumor mill all react. */
    public void onBotDied(BotIdentity bot, Player killer, Location where) {
        long now = System.currentTimeMillis();
        String killerName = killer == null ? "something" : killer.getName();
        if (killer != null) {
            state.record(bot.id(), killer.getUniqueId(), true, SocialEvent.KILLED_ME, bot.personality(), now);
            state.remember(bot.id(), new Memory("KILLED_BY", killer.getUniqueId(), killerName + " killed me", 10, now));
        }
        for (BotIdentity other : manager.all()) {
            if (other.id().equals(bot.id()) || other.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> oe = adapter.entity(other.id());
            if (!activeNear(oe, where, 64)) continue;
            boolean close = state.relationship(other.id(), bot.id()).stance().ordinal() >= Stance.FRIENDLY.ordinal() || state.factions.sameFaction(other.id(), bot.id());
            if (close && killer != null) {
                state.record(other.id(), killer.getUniqueId(), true, SocialEvent.KILLED_FRIEND, other.personality(), now);
                state.remember(other.id(), new Memory("FRIEND_KILLED", killer.getUniqueId(), killerName + " killed " + bot.name(), 9, now));
                if (rnd.nextDouble() < 0.7) ambient(other, AmbientTopic.MISSING_FRIEND, killer.getUniqueId());
            }
            Location me = oe.get().getLocation();
            state.hear(other.id(), new Rumor(UUID.randomUUID(), "DEATH", bot.name() + " was killed by " + killerName,
                RumorBook.where(where.getX() - me.getX(), where.getZ() - me.getZ()), 80, 0, now));
        }
        Debug.log(bot.name() + " died; nearby bots reacted");
    }

    /** A player handed the bot an item (the /mtreebots give mechanic). */
    public void onGift(BotIdentity bot, Player giver) {
        long now = System.currentTimeMillis();
        state.record(bot.id(), giver.getUniqueId(), true, SocialEvent.GIFT, bot.personality(), now);
        state.remember(bot.id(), new Memory("GIFTED_BY", giver.getUniqueId(), giver.getName() + " gave me something", 6, now));
        chat.say(bot, DialogueEngine.reply(net.mtree.livingplayers.chat.Intent.THANKS, net.mtree.livingplayers.chat.Compliance.Response.ACCEPT,
            stanceToward(bot, giver.getUniqueId()), styleOf(bot), facts(bot, giver.getName()), rnd), true);
    }

    public void onMilestone(BotIdentity bot, AmbientTopic topic) { if (rnd.nextDouble() < 0.8) ambient(bot, topic, null); }

    public void onRemoved(UUID botId) { state.forget(botId); }

    // ------------------------------------------------------------------ periodic work

    private void tick() {
        if (!manager.isReady() || !settings.get().chat().enabled()) return;
        seconds++;
        try {
            greetNearbyPlayers();
            Settings.Social sc = settings.get().social();
            if (seconds % sc.interactionIntervalSeconds() == 0) botInteractions(sc);
            if (seconds % settings.get().chat().ambientIntervalSeconds() == 0) ambientComment();
            if (seconds % 3600 == 0) state.decayAll(System.currentTimeMillis(), settings.get().social().halfLifeDays() * 24L * 3600_000L);
            if (seconds % settings.get().persistence().saveIntervalSeconds() == 0 && state.takeDirty()) {
                storage.saveSocial(state.snapshot()).whenComplete((v, err) -> { if (err != null) { plugin.getLogger().log(Level.SEVERE, "Social save failed; will retry", err); state.markDirty(); } });
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Social tick failed", ex);
        }
    }

    /** First time a bot sees a player (or after a long absence) it may say hello; familiarity grows. */
    private void greetNearbyPlayers() {
        long now = System.currentTimeMillis();
        for (BotIdentity b : manager.all()) {
            if (b.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> e = adapter.entity(b.id());
            if (e.isEmpty() || combat.inCombat(b.id())) continue;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!p.getWorld().equals(e.get().getWorld()) || p.getLocation().distanceSquared(e.get().getLocation()) > 100) continue;
                String key = b.id() + ":" + p.getUniqueId();
                Long last = greeted.get(key);
                if (last != null && now - last < 600_000) continue;
                greeted.put(key, now);
                state.record(b.id(), p.getUniqueId(), true, SocialEvent.GREETED, b.personality(), now);
                Stance st = stanceToward(b, p.getUniqueId());
                if (st == Stance.RIVAL || st == Stance.HOSTILE) ambient(b, AmbientTopic.SAW_RIVAL, p.getUniqueId());
                else if (b.personality().get(Trait.SOCIABILITY) > 0.35) chat.say(b, DialogueEngine.reply(net.mtree.livingplayers.chat.Intent.GREETING,
                    net.mtree.livingplayers.chat.Compliance.Response.ACCEPT, st, styleOf(b), facts(b, p.getName()), rnd), false);
            }
        }
    }

    private void botInteractions(Settings.Social sc) {
        List<BotIdentity> active = new ArrayList<>();
        for (BotIdentity b : manager.all()) if (b.state() == LifecycleState.ACTIVE && adapter.entity(b.id()).isPresent() && !combat.inCombat(b.id())) active.add(b);
        int pairs = 0;
        for (int i = 0; i < active.size() && pairs < 6; i++)
            for (int j = i + 1; j < active.size() && pairs < 6; j++) {
                BotIdentity a = active.get(i), b = active.get(j);
                if (!activeNear(adapter.entity(a.id()), adapter.entity(b.id()).orElseThrow().getLocation(), sc.interactionRadius())) continue;
                pairs++;
                interact(a, b, sc);
            }
    }

    private void interact(BotIdentity a, BotIdentity b, Settings.Social sc) {
        long now = System.currentTimeMillis();
        SocialEvent ev = compatibility(a.personality(), b.personality());
        state.record(a.id(), b.id(), false, ev, a.personality(), now);
        state.record(b.id(), a.id(), false, ev, b.personality(), now);
        BotIdentity speaker = rnd.nextBoolean() ? a : b, listener = speaker == a ? b : a;
        shareRumor(speaker, listener);

        if (!sc.factionsEnabled() || !sc.autoForm()) return;
        Relationship ab = state.relationship(a.id(), b.id()), ba = state.relationship(b.id(), a.id());
        FactionRegistry f = state.factions;
        if (f.of(a.id()).isEmpty() && f.of(b.id()).isEmpty()) {
            if (FactionFormation.shouldForm(ab, ba, a.personality(), b.personality(), sc.minAffinityToForm())) {
                BotIdentity founder = a.personality().get(Trait.COOPERATION) >= b.personality().get(Trait.COOPERATION) ? a : b, other = founder == a ? b : a;
                String name = factionName(sc, founder.name());
                if (f.create(name, founder.id(), now).ok()) {
                    f.join(f.of(founder.id()).orElseThrow().id(), other.id());
                    state.markDirty();
                    plugin.getLogger().info("Bots " + founder.name() + " and " + other.name() + " formed the faction '" + name + "'");
                    chat.say(founder, "let's stick together, " + other.name(), false);
                }
            }
        } else if (f.of(a.id()).isPresent() != f.of(b.id()).isPresent()) {
            BotIdentity member = f.of(a.id()).isPresent() ? a : b, loner = member == a ? b : a;
            FactionRegistry.Faction fac = f.of(member.id()).orElseThrow();
            double avg = fac.members().stream().mapToDouble(m -> state.relationship(loner.id(), m).affinity()).average().orElse(0);
            if (FactionFormation.shouldJoin(avg, loner.personality(), sc.minAffinityToForm()) && f.join(fac.id(), loner.id()).ok()) {
                state.markDirty();
                plugin.getLogger().info("Bot " + loner.name() + " joined the faction '" + fac.name() + "'");
            }
        }
    }

    /** Temperament decides whether time together warms or sours a pair. */
    static SocialEvent compatibility(Personality a, Personality b) {
        double fa = a.get(Trait.FRIENDLINESS), fb = b.get(Trait.FRIENDLINESS);
        if (fa >= 0.45 && fb >= 0.45) return SocialEvent.SHARED_TIME;
        if ((a.get(Trait.AGGRESSION) >= 0.7 && fb < 0.4) || (b.get(Trait.AGGRESSION) >= 0.7 && fa < 0.4)) return SocialEvent.BICKERED;
        return SocialEvent.CHATTED;
    }

    private static String factionName(Settings.Social sc, String founder) {
        List<String> prefixes = sc.namePrefixes();
        String prefix = prefixes.get(new Random().nextInt(prefixes.size()));
        String n = prefix + " " + founder;
        return n.length() <= 24 ? n : n.substring(0, 24).trim();
    }

    private void shareRumor(BotIdentity speaker, BotIdentity listener) {
        Optional<Rumor> r = state.of(speaker.id()).rumors.pickToShare(rnd);
        if (r.isEmpty()) return;
        Rumor told = RumorBook.retell(r.get(), rnd);
        if (told.confidence() < RumorBook.MIN_SHAREABLE_CONFIDENCE) return;
        state.hear(listener.id(), told);
        if (rnd.nextDouble() < 0.5) chat.say(speaker, "i heard " + told.subject() + ", " + told.where(), false); // coarse place, never coordinates
    }

    private void ambientComment() {
        long now = System.currentTimeMillis();
        List<BotIdentity> candidates = new ArrayList<>();
        for (BotIdentity b : manager.all()) if (b.state() == LifecycleState.ACTIVE && !combat.inCombat(b.id()) && adapter.entity(b.id()).isPresent()
            && now - lastAmbient.getOrDefault(b.id(), 0L) > settings.get().chat().ambientIntervalSeconds() * 1000L) candidates.add(b);
        if (candidates.isEmpty()) return;
        BotIdentity b = candidates.get(rnd.nextInt(candidates.size()));
        World w = adapter.entity(b.id()).orElseThrow().getWorld();
        AmbientTopic topic = null;
        long time = w.getTime();
        if (time >= 13000 && time < 23000 && now - nightSaid.getOrDefault(b.id(), 0L) > 600_000) { topic = AmbientTopic.NIGHT_FALLS; nightSaid.put(b.id(), now); }
        else if (w.hasStorm() && rnd.nextDouble() < 0.5) topic = AmbientTopic.RAIN;
        if (topic == null) return;
        lastAmbient.put(b.id(), now);
        ambient(b, topic, null);
    }
}
