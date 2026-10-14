package net.mtree.livingplayers.chat;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Optional;
import java.util.function.Supplier;

/** Delivers bot messages like player chat ("<Name> text") to nearby players, after sanitising and rate limiting. Main thread. */
public final class BotChat {
    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotEntityAdapter adapter;
    private ChatLimiter limiter;

    public BotChat(Plugin plugin, Supplier<Settings> settings, BotEntityAdapter adapter) {
        this.plugin = plugin; this.settings = settings; this.adapter = adapter;
        rebuildLimiter();
    }

    public void rebuildLimiter() {
        Settings.Chat c = settings.get().chat();
        limiter = new ChatLimiter(c.botCooldownSeconds() * 1000L, c.globalMaxPerMinute());
    }

    /** @return true if the message was actually sent */
    public boolean say(BotIdentity bot, String text, boolean directReply) {
        Settings.Chat cfg = settings.get().chat();
        if (!cfg.enabled()) return false;
        Optional<String> clean = ReplySanitizer.clean(text, cfg.maxChars());
        Optional<LivingEntity> e = adapter.entity(bot.id());
        if (clean.isEmpty() || e.isEmpty()) return false;
        if (!limiter.tryAcquire(bot.id(), System.currentTimeMillis(), directReply)) return false;
        Location at = e.get().getLocation();
        String line = "<" + bot.name() + "> " + clean.get();
        double r2 = (double) cfg.range() * cfg.range();
        for (Player p : Bukkit.getOnlinePlayers())
            if (cfg.range() == 0 || (p.getWorld().equals(at.getWorld()) && p.getLocation().distanceSquared(at) <= r2)) p.sendMessage(line);
        plugin.getLogger().info("[bot chat] " + line);
        return true;
    }
}
