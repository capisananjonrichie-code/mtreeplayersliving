package net.mtree.livingplayers.core;

import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Detects OPTIONAL plugins at runtime by name. Nothing here is a hard dependency and no third-party API is
 * called: Stage 1 only reports presence and documents what (if anything) the plugin does with each one.
 * "Detected" never means "tested compatible" - see the README compatibility table.
 */
public final class IntegrationManager {
    /** plugin name -> what MTREE Living Players currently does with it. */
    private static final Map<String, String> KNOWN = new LinkedHashMap<>();
    static {
        KNOWN.put("LuckPerms", "none needed: commands use standard Bukkit permission nodes, which LuckPerms resolves");
        KNOWN.put("SkinsRestorer", "not used: bots use Paper profile lookups; SkinsRestorer targets real players");
        KNOWN.put("Geyser-Spigot", "none: Bedrock rendering of Mannequin entities is UNVERIFIED (needs a live test)");
        KNOWN.put("floodgate", "none: Bedrock players are ordinary Players and are counted as such");
        KNOWN.put("ViaVersion", "none: no client-version assumptions are made");
        KNOWN.put("Essentials", "none: no commands, homes or warps are touched");
        KNOWN.put("Graves", "risk: bots are not Players, so no grave should be created; UNVERIFIED");
        KNOWN.put("HeadDrop", "risk: may react to non-player LivingEntity deaths; UNVERIFIED");
        KNOWN.put("LevelledMobs", "risk: may treat Mannequins as mobs; UNVERIFIED (do not configure it to level them)");
        KNOWN.put("ProtocolLib", "not used");
        KNOWN.put("WorldGuard", "claim plugin: will gate block interaction (Stage 4); spawning ignores it");
        KNOWN.put("GriefPrevention", "claim plugin: will gate block interaction (Stage 4)");
        KNOWN.put("Towny", "claim plugin: will gate block interaction (Stage 4)");
        KNOWN.put("Lands", "claim plugin: will gate block interaction (Stage 4)");
        KNOWN.put("Dynamic_World_Events", "not used yet (Stage 7)");
        KNOWN.put("voicechat", "none: voice chat does NOT work for bots");
    }
    private static final List<String> CLAIM_PLUGINS = List.of("WorldGuard", "GriefPrevention", "Towny", "Lands");

    public record Status(String plugin, boolean present, boolean enabledInConfig, String note) {}

    private final Supplier<Settings> settings;

    public IntegrationManager(Supplier<Settings> settings) { this.settings = settings; }

    public boolean present(String plugin) {
        return Bukkit.getPluginManager().isPluginEnabled(plugin) && settings.get().integrations().isEnabled(plugin);
    }

    public List<String> claimPluginsPresent() {
        List<String> out = new ArrayList<>();
        for (String p : CLAIM_PLUGINS) if (Bukkit.getPluginManager().isPluginEnabled(p)) out.add(p);
        return out;
    }

    public List<Status> report() {
        List<Status> out = new ArrayList<>();
        KNOWN.forEach((name, note) -> out.add(new Status(name, Bukkit.getPluginManager().isPluginEnabled(name),
            settings.get().integrations().isEnabled(name), note)));
        return out;
    }
}
