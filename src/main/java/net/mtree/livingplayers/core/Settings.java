package net.mtree.livingplayers.core;

import net.mtree.livingplayers.bot.CombatSkill;
import net.mtree.livingplayers.bot.PersonalityTemplate;
import net.mtree.livingplayers.combat.SkillProfile;
import net.mtree.livingplayers.combat.TargetSelector;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Immutable, validated snapshot of all configuration read by the code. Bukkit-free so it is unit-testable. */
public record Settings(
    boolean debug,
    Persistence persistence,
    Lifecycle lifecycle,
    Death death,
    Population population,
    SpawnCfg spawn,
    Navigation navigation,
    Combat combat,
    Survival survival,
    Building building,
    Chat chat,
    Social social,
    BotsCfg bots,
    List<PersonalityTemplate> personalities,
    int goalsPerBot,
    Map<CombatSkill, Double> skillWeights,
    WorldsCfg worlds,
    Integrations integrations) {

    public record Persistence(int saveIntervalSeconds, int backupsToKeep) {}

    public record Lifecycle(int maxActiveBots, int reconcileIntervalTicks, int despawnDistance, int despawnGraceSeconds) {}

    public record Death(boolean dropInventory, boolean respawnEnabled, int respawnDelaySeconds,
                        boolean keepExperience, boolean playersCanDamageBots) {}

    public record Population(boolean enabled, int maxPopulation, double targetActivePerPlayer,
                             int spawnIntervalSeconds, double spawnChance, double newIdentityChance,
                             int minEncounterDistance, int maxEncounterDistance,
                             int encounterCooldownSeconds, int botSpawnCooldownSeconds,
                             boolean avoidLineOfSight, int minDistanceFromAnyPlayer,
                             int maxAttemptsPerCycle, double tpsThrottleBelow) {}

    public record SpawnCfg(int minY, int maxY, boolean allowWater, int hazardRadius, int avoidArtificialRadius) {}

    public record Navigation(boolean enabled, int maxPathNodes, int maxPathDistance, int maxDrop, int maxPathCalcsPerTick,
                             int repathLimit, double walkSpeed, double sprintSpeed, boolean wanderEnabled,
                             int wanderRadiusMin, int wanderRadiusMax, int wanderPauseMinSeconds, int wanderPauseMaxSeconds,
                             int avoidPlayerDistance, double followDistance) {}

    public record Combat(boolean enabled, boolean vitalsEnabled, TargetSelector.PlayerPolicy attackPlayers,
                         int retaliationWindowSeconds, double reach, int scanIntervalTicks, boolean weaponDurability,
                         Map<CombatSkill, SkillProfile> profiles) {
        public SkillProfile profile(CombatSkill s) { return profiles.getOrDefault(s, SkillProfile.defaults(s)); }
    }

    public record Survival(boolean enabled, boolean blockInteractionEnabled, double reach, boolean allowWithClaimPlugins,
                           int avoidArtificialRadius, int searchRadius, int scanBudget, int scanIntervalSeconds,
                           int failureBackoffSeconds, int craftTicks) {}

    public record Building(boolean enabled, boolean autoStart, double minBuildingPreference, int siteSearchRadius,
                           int maxSiteCandidates, int siteMargin, int placeIntervalTicks, int maxSkipped, int maxProjectsPerBot) {}

    public record Llm(boolean enabled, String endpoint, String apiKeyEnv, int timeoutMs, int maxPerMinute, String systemPrompt) {}

    public record Chat(boolean enabled, int range, int ambientIntervalSeconds, int botCooldownSeconds, int globalMaxPerMinute,
                       int replyDelayMinTicks, int replyDelayMaxTicks, int conversationRadius, int maxChars, Llm llm) {}

    public record Social(boolean factionsEnabled, boolean autoForm, double minAffinityToForm, int maxFactionSize, int assistRadius,
                         boolean assistAgainstPlayers, int interactionRadius, int interactionIntervalSeconds, int memoryMax, int rumorMax,
                         int halfLifeDays, List<String> namePrefixes) {}

    public record BotsCfg(List<String> namePool, String skinMode, List<String> skinPool, boolean showNameTag,
                          String description, double startingHealth, int startingHunger, boolean dropInventoryOnRemove) {}

    public record Integrations(boolean logDetected, Map<String, Boolean> enabled) {
        public boolean isEnabled(String plugin) { return enabled.getOrDefault(plugin.toLowerCase(Locale.ROOT), true); }
    }

    public record WorldOverride(Boolean populationEnabled, Integer maxActive) {}

    public record ProtectedRegion(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public boolean contains(String worldName, double x, double y, double z) {
            return world.equalsIgnoreCase(worldName)
                && x >= Math.min(minX, maxX) && x < Math.max(minX, maxX) + 1
                && y >= Math.min(minY, maxY) && y < Math.max(minY, maxY) + 1
                && z >= Math.min(minZ, maxZ) && z < Math.max(minZ, maxZ) + 1;
        }
    }

    /** World rules. All names are stored lower-case. */
    public record WorldsCfg(Set<String> allowedEnvironments, Set<String> allowlist, Set<String> denylist,
                            Map<String, WorldOverride> overrides, List<ProtectedRegion> regions) {

        /** Stage 1 only ever populates NORMAL (overworld-style) dimensions regardless of config. */
        public boolean populationAllowed(String worldName, String environment) {
            String w = worldName.toLowerCase(Locale.ROOT);
            if (!"NORMAL".equals(environment.toUpperCase(Locale.ROOT))) return false;
            if (!allowedEnvironments.contains("NORMAL")) return false;
            if (denylist.contains(w)) return false;
            if (!allowlist.isEmpty() && !allowlist.contains(w)) return false;
            WorldOverride o = overrides.get(w);
            return o == null || o.populationEnabled() == null || o.populationEnabled();
        }

        public int maxActiveFor(String worldName) {
            WorldOverride o = overrides.get(worldName.toLowerCase(Locale.ROOT));
            return o == null || o.maxActive() == null ? Integer.MAX_VALUE : Math.max(0, o.maxActive());
        }

        public ProtectedRegion regionAt(String worldName, double x, double y, double z) {
            for (ProtectedRegion r : regions) if (r.contains(worldName, x, y, z)) return r;
            return null;
        }
    }
}
