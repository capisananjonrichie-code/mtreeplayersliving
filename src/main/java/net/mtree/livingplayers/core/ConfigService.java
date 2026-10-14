package net.mtree.livingplayers.core;

import net.mtree.livingplayers.bot.CombatSkill;
import net.mtree.livingplayers.bot.PersonalityGenerator;
import net.mtree.livingplayers.bot.PersonalityTemplate;
import net.mtree.livingplayers.bot.PersonalityTemplate.Range;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.combat.SkillProfile;
import net.mtree.livingplayers.combat.TargetSelector;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * Loads the ten YAML files. Missing files are created from the jar; missing KEYS fall back to the jar's
 * defaults (so upgrades never break an existing config). Values are clamped, never trusted.
 */
public final class ConfigService {
    public static final List<String> FILES = List.of("config.yml", "bots.yml", "personalities.yml", "combat.yml",
        "survival.yml", "building.yml", "factions.yml", "chat.yml", "worlds.yml", "integrations.yml");

    private final JavaPlugin plugin;
    private volatile Settings settings;

    public ConfigService(JavaPlugin plugin) { this.plugin = plugin; }

    public Settings settings() { return settings; }

    public void load() {
        Map<String, YamlConfiguration> f = new HashMap<>();
        for (String name : FILES) f.put(name, read(name));
        settings = parse(f);
    }

    private YamlConfiguration read(String name) {
        File file = new File(plugin.getDataFolder(), name);
        if (!file.exists()) plugin.saveResource(name, false);
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource(name)) {
            if (in != null) cfg.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not read embedded defaults for " + name, e);
        }
        return cfg;
    }

    private static int i(ConfigurationSection s, String p, int min, int max) { return Math.max(min, Math.min(max, s.getInt(p))); }
    private static double d(ConfigurationSection s, String p, double min, double max) { return Math.max(min, Math.min(max, s.getDouble(p))); }

    private Settings parse(Map<String, YamlConfiguration> f) {
        YamlConfiguration c = f.get("config.yml");
        Settings.Persistence per = new Settings.Persistence(i(c, "persistence.save-interval-seconds", 15, 3600), i(c, "persistence.backups-to-keep", 0, 50));
        Settings.Lifecycle life = new Settings.Lifecycle(i(c, "lifecycle.max-active-bots", 0, 100), i(c, "lifecycle.reconcile-interval-ticks", 10, 1200),
            i(c, "lifecycle.despawn-distance", 32, 1000), i(c, "lifecycle.despawn-grace-seconds", 5, 3600));
        Settings.Death death = new Settings.Death(c.getBoolean("death.drop-inventory"), c.getBoolean("death.respawn-enabled"),
            i(c, "death.respawn-delay-seconds", 0, 86400), c.getBoolean("death.keep-experience"), c.getBoolean("death.players-can-damage-bots"));

        int minEnc = i(c, "population.min-encounter-distance", 8, 256);
        int maxEnc = Math.max(minEnc + 8, i(c, "population.max-encounter-distance", 16, 512));
        Settings.Population pop = new Settings.Population(c.getBoolean("population.enabled"), i(c, "population.max-population", 0, 500),
            d(c, "population.target-active-per-player", 0, 10), i(c, "population.spawn-interval-seconds", 10, 3600),
            d(c, "population.spawn-chance", 0, 1), d(c, "population.new-identity-chance", 0, 1), minEnc, maxEnc,
            i(c, "population.encounter-cooldown-seconds", 0, 86400), i(c, "population.bot-spawn-cooldown-seconds", 0, 86400),
            c.getBoolean("population.avoid-line-of-sight"), i(c, "population.min-distance-from-any-player", 0, 256),
            i(c, "population.max-attempts-per-cycle", 1, 100), d(c, "population.tps-throttle-below", 0, 20));
        Settings.SpawnCfg spawn = new Settings.SpawnCfg(i(c, "spawn-safety.min-y", -2048, 2048), i(c, "spawn-safety.max-y", -2048, 2048),
            c.getBoolean("spawn-safety.allow-water"), i(c, "spawn-safety.hazard-radius", 0, 4), i(c, "spawn-safety.avoid-artificial-blocks-radius", 0, 8));

        int wMin = i(c, "navigation.wander-radius-min", 3, 64);
        int pMin = i(c, "navigation.wander-pause-min-seconds", 1, 600);
        Settings.Navigation nav = new Settings.Navigation(c.getBoolean("navigation.enabled"), i(c, "navigation.max-path-nodes", 50, 20000),
            i(c, "navigation.max-path-distance", 8, 128), i(c, "navigation.max-drop", 1, 4), i(c, "navigation.max-path-calcs-per-tick", 1, 8),
            i(c, "navigation.repath-limit", 0, 10), d(c, "navigation.walk-speed", 0.05, 0.4), d(c, "navigation.sprint-speed", 0.05, 0.5),
            c.getBoolean("navigation.wander-enabled"), wMin, Math.max(wMin + 2, i(c, "navigation.wander-radius-max", 5, 128)),
            pMin, Math.max(pMin, i(c, "navigation.wander-pause-max-seconds", 1, 3600)),
            i(c, "navigation.avoid-player-distance", 0, 128), d(c, "navigation.follow-distance", 1.5, 16));

        YamlConfiguration b = f.get("bots.yml");
        Settings.BotsCfg bots = new Settings.BotsCfg(b.getStringList("name-pool"), b.getString("skins.mode", "default").toLowerCase(Locale.ROOT),
            b.getStringList("skins.pool"), b.getBoolean("appearance.show-name-tag"), b.getString("appearance.description", ""),
            d(b, "starting.health", 1, 20), i(b, "starting.hunger", 0, 20), b.getBoolean("removal.drop-inventory-on-remove"));

        YamlConfiguration p = f.get("personalities.yml");
        List<PersonalityTemplate> templates = parseTemplates(p);
        int goals = i(p, "goals-per-bot", 1, 6);

        Map<CombatSkill, Double> skillWeights = new LinkedHashMap<>();
        ConfigurationSection sw = f.get("combat.yml").getConfigurationSection("skill-spawn-weights");
        if (sw != null) for (String k : sw.getKeys(false)) {
            CombatSkill sk = CombatSkill.parse(k, null);
            if (sk != null) skillWeights.put(sk, Math.max(0, sw.getDouble(k)));
        }
        if (skillWeights.values().stream().mapToDouble(Double::doubleValue).sum() <= 0) skillWeights = PersonalityGenerator.defaultSkillWeights();

        Settings.Combat combat = parseCombat(f.get("combat.yml"));
        YamlConfiguration bd = f.get("building.yml");
        Settings.Building building = new Settings.Building(bd.getBoolean("enabled"), bd.getBoolean("auto-start"),
            d(bd, "min-building-preference", 0, 1), i(bd, "site-search-radius", 6, 48), i(bd, "max-site-candidates", 1, 40),
            i(bd, "site-margin", 1, 8), i(bd, "place-interval-ticks", 2, 100), i(bd, "max-skipped-blocks", 0, 100), i(bd, "max-projects-per-bot", 0, 10));
        YamlConfiguration ch = f.get("chat.yml"), fc = f.get("factions.yml");
        int dMin = i(ch, "reply-delay-ticks-min", 0, 200);
        Settings.Chat chat = new Settings.Chat(ch.getBoolean("enabled"), i(ch, "range", 0, 256), i(ch, "ambient-interval-seconds", 10, 3600),
            i(ch, "bot-cooldown-seconds", 5, 3600), i(ch, "global-max-per-minute", 1, 60), dMin, Math.max(dMin, i(ch, "reply-delay-ticks-max", 0, 400)),
            i(ch, "conversation-radius", 3, 48), i(ch, "max-message-chars", 20, 200),
            new Settings.Llm(ch.getBoolean("llm.enabled"), String.valueOf(ch.getString("llm.endpoint", "")), String.valueOf(ch.getString("llm.api-key-env", "")),
                i(ch, "llm.timeout-ms", 500, 20000), i(ch, "llm.max-requests-per-minute", 1, 120), String.valueOf(ch.getString("llm.system-prompt", ""))));
        List<String> prefixes = fc.getStringList("name-prefixes");
        Settings.Social social = new Settings.Social(fc.getBoolean("enabled"), fc.getBoolean("auto-form"), d(fc, "min-affinity-to-form", 20, 100),
            i(fc, "max-faction-size", 2, 12), i(fc, "assist-radius", 4, 64), fc.getBoolean("assist-against-players"),
            i(fc, "interaction-radius", 4, 32), i(fc, "interaction-interval-seconds", 10, 600), i(ch, "memory.max-entries-per-bot", 5, 200),
            i(ch, "memory.max-rumors-per-bot", 2, 50), i(ch, "memory.relationship-half-life-days", 1, 365), prefixes.isEmpty() ? List.of("Tropa") : prefixes);
        YamlConfiguration sv = f.get("survival.yml");
        Settings.Survival survival = new Settings.Survival(sv.getBoolean("enabled"), sv.getBoolean("block-interaction.enabled"),
            d(sv, "block-interaction.reach", 2.0, 5.0), sv.getBoolean("block-interaction.allow-with-claim-plugins"),
            i(sv, "block-interaction.avoid-artificial-radius", 1, 6), i(sv, "gathering.search-radius", 4, 20),
            i(sv, "gathering.scan-budget-blocks", 500, 20000), i(sv, "gathering.scan-interval-seconds", 1, 120),
            i(sv, "gathering.failure-backoff-seconds", 5, 3600), i(sv, "crafting.craft-ticks", 5, 200));

        return new Settings(c.getBoolean("debug"), per, life, death, pop, spawn, nav, combat, survival, building, chat, social, bots, templates, goals, skillWeights,
            parseWorlds(f.get("worlds.yml")), parseIntegrations(f.get("integrations.yml")));
    }

    private Settings.Combat parseCombat(YamlConfiguration y) {
        TargetSelector.PlayerPolicy policy = "NEVER".equalsIgnoreCase(y.getString("attack-players", "RETALIATE_ONLY"))
            ? TargetSelector.PlayerPolicy.NEVER : TargetSelector.PlayerPolicy.RETALIATE_ONLY;
        Map<CombatSkill, SkillProfile> profiles = new java.util.EnumMap<>(CombatSkill.class);
        for (CombatSkill sk : CombatSkill.values()) {
            SkillProfile d = SkillProfile.defaults(sk);
            String b = "skills." + sk.name() + ".";
            profiles.put(sk, new SkillProfile(
                Math.max(2, Math.min(40, y.getInt(b + "reaction-ticks", d.reactionTicks()))),
                Math.max(5, Math.min(90, y.getDouble(b + "facing-tolerance-deg", d.facingToleranceDeg()))),
                Math.max(3, Math.min(45, y.getDouble(b + "turn-rate-deg", d.turnRateDeg()))),
                Math.max(0.01, Math.min(0.9, y.getDouble(b + "mistake-chance", d.mistakeChance()))),
                Math.max(0, Math.min(1, y.getDouble(b + "crit-chance", d.critChance()))),
                Math.max(0, Math.min(1, y.getDouble(b + "strafe-chance", d.strafeChance()))),
                Math.max(0, Math.min(1, y.getDouble(b + "eat-below", d.eatBelow()))),
                Math.max(0, Math.min(0.9, y.getDouble(b + "retreat-below", d.retreatBelow()))),
                Math.max(0.2, Math.min(1, y.getDouble(b + "reengage-above", d.reengageAbove()))),
                Math.max(1, Math.min(10, y.getInt(b + "max-opponents", d.maxOpponents()))),
                Math.max(6, Math.min(48, y.getDouble(b + "awareness-range", d.awarenessRange())))));
        }
        return new Settings.Combat(y.getBoolean("enabled", true), y.getBoolean("vitals-enabled", true), policy,
            Math.max(1, Math.min(600, y.getInt("retaliation-window-seconds", 30))), Math.max(2.0, Math.min(4.5, y.getDouble("reach", 3.0))),
            Math.max(2, Math.min(100, y.getInt("scan-interval-ticks", 10))), y.getBoolean("weapon-durability", true), profiles);
    }

    private List<PersonalityTemplate> parseTemplates(YamlConfiguration p) {
        List<PersonalityTemplate> out = new ArrayList<>();
        ConfigurationSection arch = p.getConfigurationSection("archetypes");
        if (arch != null) for (String id : arch.getKeys(false)) {
            ConfigurationSection a = arch.getConfigurationSection(id);
            if (a == null) continue;
            Map<Trait, Range> ranges = new EnumMap<>(Trait.class);
            ConfigurationSection traits = a.getConfigurationSection("traits");
            if (traits != null) for (String tk : traits.getKeys(false)) {
                Trait t = Trait.fromKey(tk);
                List<Double> r = traits.getDoubleList(tk);
                if (t == null || r.size() != 2) { plugin.getLogger().warning("personalities.yml: bad trait entry '" + id + "." + tk + "' ignored"); continue; }
                ranges.put(t, new Range(r.get(0), r.get(1)));
            }
            out.add(new PersonalityTemplate(id, Math.max(0, a.getDouble("weight", 1.0)), ranges));
        }
        if (out.isEmpty()) { plugin.getLogger().warning("personalities.yml has no usable archetypes; using built-in defaults"); return PersonalityGenerator.DEFAULTS; }
        return out;
    }

    private Settings.WorldsCfg parseWorlds(YamlConfiguration w) {
        Set<String> envs = new HashSet<>();
        for (String s : w.getStringList("allowed-environments")) envs.add(s.toUpperCase(Locale.ROOT));
        Map<String, Settings.WorldOverride> overrides = new HashMap<>();
        ConfigurationSection o = w.getConfigurationSection("overrides");
        if (o != null) for (String name : o.getKeys(false)) {
            ConfigurationSection s = o.getConfigurationSection(name);
            if (s == null) continue;
            overrides.put(name.toLowerCase(Locale.ROOT), new Settings.WorldOverride(
                s.contains("population-enabled") ? s.getBoolean("population-enabled") : null,
                s.contains("max-active") ? Math.max(0, s.getInt("max-active")) : null));
        }
        List<Settings.ProtectedRegion> regions = new ArrayList<>();
        for (Map<?, ?> m : w.getMapList("protected-regions")) {
            try {
                List<?> mn = (List<?>) m.get("min"), mx = (List<?>) m.get("max");
                regions.add(new Settings.ProtectedRegion(String.valueOf(m.get("name")), String.valueOf(m.get("world")),
                    ((Number) mn.get(0)).intValue(), ((Number) mn.get(1)).intValue(), ((Number) mn.get(2)).intValue(),
                    ((Number) mx.get(0)).intValue(), ((Number) mx.get(1)).intValue(), ((Number) mx.get(2)).intValue()));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("worlds.yml: invalid protected-regions entry " + m + " ignored");
            }
        }
        return new Settings.WorldsCfg(envs, lower(w.getStringList("allowlist")), lower(w.getStringList("denylist")), overrides, regions);
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new HashSet<>();
        for (String x : in) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }

    private Settings.Integrations parseIntegrations(YamlConfiguration c) {
        Map<String, Boolean> m = new HashMap<>();
        ConfigurationSection s = c.getConfigurationSection("plugins");
        if (s != null) for (String k : s.getKeys(false)) m.put(k.toLowerCase(Locale.ROOT), s.getBoolean(k + ".enabled", true));
        return new Settings.Integrations(c.getBoolean("log-detected-plugins"), m);
    }
}
