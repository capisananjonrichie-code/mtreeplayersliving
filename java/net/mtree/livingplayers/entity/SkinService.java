package net.mtree.livingplayers.entity;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.SkinSpec;
import net.mtree.livingplayers.core.Debug;
import org.bukkit.Bukkit;
import org.bukkit.entity.Mannequin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Applies skins to mannequins. A skin problem NEVER blocks spawning: failures leave the default skin.
 * "player:" lookups use Paper's async profile update (network, off-thread) and apply on the main thread.
 * Cache and Bukkit calls are main-thread only.
 */
public final class SkinService {
    private final Logger log;
    private final Executor mainThread;
    private final Map<String, PlayerProfile> resolvedByName = new HashMap<>(); // main thread only
    private final Map<String, Long> failedAt = new HashMap<>();

    public SkinService(Logger log, Executor mainThread) {
        this.log = log;
        this.mainThread = mainThread;
    }

    /** Applies the bot's skin to a freshly spawned mannequin (immediate for default/texture, async for player lookups). */
    public void apply(Mannequin m, BotIdentity bot) {
        SkinSpec spec = SkinSpec.parse(bot.skinSpec());
        switch (spec.kind()) {
            case DEFAULT -> { /* vanilla mannequin skin */ }
            case TEXTURE -> {
                PlayerProfile p = Bukkit.createProfile(bot.id(), bot.name());
                p.setProperty(new ProfileProperty("textures", spec.value(), spec.signature()));
                m.setProfile(ResolvableProfile.resolvableProfile(p));
            }
            case PLAYER -> applyPlayerSkin(m, bot, spec.name());
        }
    }

    private void applyPlayerSkin(Mannequin m, BotIdentity bot, String skinOwner) {
        PlayerProfile cached = resolvedByName.get(skinOwner.toLowerCase());
        if (cached != null) { m.setProfile(ResolvableProfile.resolvableProfile(masked(cached, bot))); return; }
        Long failed = failedAt.get(skinOwner.toLowerCase());
        if (failed != null && System.currentTimeMillis() - failed < 600_000) return; // retry after 10 min

        PlayerProfile lookup = Bukkit.createProfile(skinOwner);
        lookup.update().whenComplete((resolved, err) -> mainThread.execute(() -> {
            if (err != null || resolved == null || !resolved.hasTextures()) {
                failedAt.put(skinOwner.toLowerCase(), System.currentTimeMillis());
                Debug.log("Skin lookup for '" + skinOwner + "' failed; bot " + bot.name() + " keeps the default skin" + (err != null ? " (" + err.getMessage() + ")" : ""));
                return;
            }
            resolvedByName.put(skinOwner.toLowerCase(), resolved);
            if (m.isValid()) m.setProfile(ResolvableProfile.resolvableProfile(masked(resolved, bot)));
        }));
    }

    /** Copies only the texture properties onto a profile carrying the BOT's uuid/name - never the real player's identity. */
    private static PlayerProfile masked(PlayerProfile source, BotIdentity bot) {
        PlayerProfile p = Bukkit.createProfile(bot.id(), bot.name());
        p.setProperties(source.getProperties());
        return p;
    }
}
