package net.mtree.livingplayers.chat;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.building.BuildingAI;
import net.mtree.livingplayers.chat.Compliance.Response;
import net.mtree.livingplayers.chat.DialogueEngine.Facts;
import net.mtree.livingplayers.chat.DialogueEngine.Style;
import net.mtree.livingplayers.combat.CombatAI;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.nav.BotNavigator;
import net.mtree.livingplayers.social.MemoryStore;
import net.mtree.livingplayers.social.Relationship;
import net.mtree.livingplayers.social.SocialEvent;
import net.mtree.livingplayers.social.SocialState;
import net.mtree.livingplayers.survival.SurvivalAI;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Turns a player's chat line into (maybe) a bot reply and (maybe) an action.
 *   1. choose the bot being addressed (by name, else the nearest one in earshot)
 *   2. classify the message into a FIXED intent (IntentClassifier)
 *   3. decide whether the bot is willing (Compliance: feelings, temperament, busyness)
 *   4. reply from templates; for plain small talk the optional language model may supply the TEXT instead
 *   5. after a typing delay, perform the action through the same validated navigator calls the commands use
 * The language model never sees or chooses actions, and its text is sanitised like everything else.
 */
public final class ConversationService {
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final BotNavigator navigator;
    private final CombatAI combat;
    private final SurvivalAI survival;
    private final BuildingAI building;
    private final BotChat chat;
    private final SocialState social;
    private final Random rnd = new Random();
    private volatile LanguageModelAdapter llm;

    public ConversationService(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter, BotNavigator navigator,
                               CombatAI combat, SurvivalAI survival, BuildingAI building, BotChat chat, SocialState social) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter; this.navigator = navigator;
        this.combat = combat; this.survival = survival; this.building = building; this.chat = chat; this.social = social;
        configureModel();
    }

    /** (Re)builds the optional model adapter from config. Anything misconfigured simply leaves it off. */
    public void configureModel() {
        Settings.Llm c = settings.get().chat().llm();
        llm = null;
        if (!c.enabled() || c.endpoint().isBlank()) return;
        try {
            String key = c.apiKeyEnv().isBlank() ? null : System.getenv(c.apiKeyEnv());
            llm = new HttpLanguageModelAdapter(URI.create(c.endpoint()), key, Duration.ofMillis(c.timeoutMs()), c.maxPerMinute());
            plugin.getLogger().info("Optional language model enabled (reply text only). Endpoint host: " + URI.create(c.endpoint()).getHost());
        } catch (RuntimeException e) {
            plugin.getLogger().warning("chat.yml llm.endpoint is invalid; language model stays OFF: " + e.getMessage());
        }
    }

    public void onPlayerMessage(Player p, String text) {
        Settings.Chat cfg = settings.get().chat();
        if (!cfg.enabled() || !manager.isReady() || text == null || text.isBlank()) return;
        BotIdentity bot = addressed(p, text, cfg);
        if (bot == null) return;

        Intent intent = IntentClassifier.classify(text);
        long now = System.currentTimeMillis();
        UUID pid = p.getUniqueId();
        Relationship rel = social.relationship(bot.id(), pid);
        Response response = Compliance.decide(intent, rel, bot.personality(), combat.inCombat(bot.id()));

        switch (intent) {
            case GREETING -> social.record(bot.id(), pid, true, SocialEvent.GREETED, bot.personality(), now);
            case THANKS -> social.record(bot.id(), pid, true, SocialEvent.THANKED, bot.personality(), now);
            case INSULT -> {
                social.record(bot.id(), pid, true, SocialEvent.INSULTED, bot.personality(), now);
                social.remember(bot.id(), new MemoryStore.Memory("INSULTED_BY", pid, p.getName() + " insulted me", 4, now));
            }
            default -> social.record(bot.id(), pid, true, SocialEvent.CHATTED, bot.personality(), now);
        }
        rel = social.relationship(bot.id(), pid);

        Style style = Style.of(bot.personality(), bot.id());
        Facts facts = new Facts(p.getName(), bot.name(), activity(bot));
        String template = DialogueEngine.reply(intent, response, rel.stance(), style, facts, rnd);
        int delay = cfg.replyDelayMinTicks() + rnd.nextInt(Math.max(1, cfg.replyDelayMaxTicks() - cfg.replyDelayMinTicks() + 1));
        UUID botId = bot.id();

        LanguageModelAdapter model = llm;
        if (model != null && intent == Intent.SMALL_TALK) {
            model.reply(systemPrompt(bot), context(bot, p, rel, facts), text).whenComplete((reply, err) -> {
                String chosen = err == null && reply != null && reply.isPresent() ? reply.get() : template; // any failure -> templates
                later(delay, () -> deliver(botId, p, intent, response, chosen));
            });
        } else {
            later(delay, () -> deliver(botId, p, intent, response, template));
        }
    }

    // ------------------------------------------------------------------ internals

    private void later(int ticks, Runnable r) {
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, r, Math.max(1, ticks)); // also hops from the HTTP thread back to the main thread
    }

    private void deliver(UUID botId, Player p, Intent intent, Response response, String line) {
        Optional<BotIdentity> ob = manager.byId(botId);
        if (ob.isEmpty() || ob.get().state() != LifecycleState.ACTIVE || adapter.entity(botId).isEmpty()) return;
        BotIdentity bot = ob.get();
        if (!chat.say(bot, line, true)) return; // rate limited: stay silent and do nothing
        if (response != Response.ACCEPT || !p.isOnline()) return;
        // re-validate right before acting: things may have changed during the typing delay
        if (combat.inCombat(botId) && intent != Intent.STOP_REQUEST) return;
        switch (intent) {
            case FOLLOW_REQUEST -> navigator.follow(bot, p);
            case HELP_REQUEST -> navigator.goTo(bot, p.getLocation());
            case STOP_REQUEST -> navigator.stop(bot);
            default -> { /* conversation only */ }
        }
        Debug.log(bot.name() + " " + intent + " -> " + response);
    }

    private BotIdentity addressed(Player p, String text, Settings.Chat cfg) {
        String lower = text.toLowerCase(Locale.ROOT);
        BotIdentity best = null;
        double bestD = Double.MAX_VALUE;
        for (BotIdentity b : manager.all()) {
            if (b.state() != LifecycleState.ACTIVE) continue;
            Optional<LivingEntity> e = adapter.entity(b.id());
            if (e.isEmpty() || !e.get().getWorld().equals(p.getWorld())) continue;
            double d = e.get().getLocation().distance(p.getLocation());
            boolean named = Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(b.name().toLowerCase(Locale.ROOT)) + "(?![\\p{L}\\p{N}_])").matcher(lower).find();
            if (named ? d <= 48 : d <= cfg.conversationRadius()) {
                double score = named ? d - 1000 : d; // a named bot always beats an unnamed nearby one
                if (score < bestD) { bestD = score; best = b; }
            }
        }
        return best;
    }

    private String activity(BotIdentity b) {
        return ActivitySummary.describe(combat.inCombat(b.id()), combat.isRetreating(b.id()), combat.isEating(b.id()), building.phaseLabel(b.id()), survival.stepLabel(b.id()));
    }

    private String systemPrompt(BotIdentity b) { return settings.get().chat().llm().systemPrompt().replace("{bot}", b.name()); }

    /** What the model may know: personality gist, feelings, current activity, a few memories about THIS speaker. No coordinates, no other players' data. */
    private String context(BotIdentity b, Player p, Relationship rel, Facts facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("You feel ").append(rel.stance().name().toLowerCase(Locale.ROOT)).append(" toward ").append(p.getName()).append(". You are ").append(facts.activity()).append(". ");
        sb.append("You are ").append(b.personality().get(Trait.FRIENDLINESS) > 0.6 ? "friendly" : b.personality().get(Trait.AGGRESSION) > 0.6 ? "blunt" : "easygoing")
          .append(b.personality().get(Trait.SOCIABILITY) < 0.35 ? " and quiet" : "").append(". ");
        for (MemoryStore.Memory m : social.of(b.id()).memory.about(p.getUniqueId()).stream().limit(3).toList()) sb.append("Remember: ").append(m.text()).append(". ");
        return sb.toString();
    }
}
