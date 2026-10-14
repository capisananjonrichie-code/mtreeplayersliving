package net.mtree.livingplayers.bot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Live, main-thread-owned state of one bot. The UUID is the identity; the name is display data. */
public final class BotIdentity {
    public static final String EXTRA_RESPAWN_AT = "respawn_at";
    public static final String EXTRA_LAST_SPAWN_AT = "last_spawn_at";
    public static final String EXTRA_LAST_DEATH_AT = "last_death_at";

    private final UUID id;
    private final String name;
    private String skinSpec;
    private LifecycleState state;
    private BotLocation lastLocation;
    private double health;
    private int hunger;
    private float saturation;
    private long experience;
    private final Personality personality;
    private final CombatSkill skill;
    private final CombatStyle style;
    private List<String> goals;
    private BotLocation home;
    private String factionId;
    private final BotInventory inventory;
    private final Map<String, String> extra;
    private int deathCount;
    private final long createdAt;
    private long lastSavedAt;
    private boolean dirty;

    // runtime-only (never persisted)
    private long outOfRangeSinceMs = -1;
    private final java.util.ArrayDeque<Long> unexpectedLosses = new java.util.ArrayDeque<>();
    private boolean worldMissingWarned;

    public BotIdentity(UUID id, String name, String skinSpec, LifecycleState state, BotLocation lastLocation,
                       double health, int hunger, float saturation, long experience, Personality personality,
                       CombatSkill skill, CombatStyle style, List<String> goals, BotLocation home, String factionId,
                       BotInventory inventory, Map<String, String> extra, int deathCount, long createdAt, long lastSavedAt) {
        this.id = id; this.name = name; this.skinSpec = skinSpec; this.state = state; this.lastLocation = lastLocation;
        this.health = health; this.hunger = hunger; this.saturation = saturation; this.experience = experience;
        this.personality = personality; this.skill = skill; this.style = style; this.goals = List.copyOf(goals);
        this.home = home; this.factionId = factionId; this.inventory = inventory;
        this.extra = new LinkedHashMap<>(extra); this.deathCount = deathCount;
        this.createdAt = createdAt; this.lastSavedAt = lastSavedAt;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String skinSpec() { return skinSpec; }
    public LifecycleState state() { return state; }
    public BotLocation lastLocation() { return lastLocation; }
    public double health() { return health; }
    public int hunger() { return hunger; }
    public float saturation() { return saturation; }
    public long experience() { return experience; }
    public Personality personality() { return personality; }
    public CombatSkill skill() { return skill; }
    public CombatStyle style() { return style; }
    public List<String> goals() { return goals; }
    public BotLocation home() { return home; }
    public String factionId() { return factionId; }
    public BotInventory inventory() { return inventory; }
    public int deathCount() { return deathCount; }
    public long createdAt() { return createdAt; }
    public long lastSavedAt() { return lastSavedAt; }
    public boolean dirty() { return dirty; }
    public Map<String, String> extra() { return extra; }

    public void markDirty() { dirty = true; }
    public void markClean(long savedAt) { dirty = false; lastSavedAt = savedAt; }

    /** Returns false (and changes nothing) if the transition is not allowed. */
    public boolean setState(LifecycleState next) {
        if (!state.canTransitionTo(next)) return false;
        if (state != next) { state = next; dirty = true; }
        return true;
    }

    public void setLastLocation(BotLocation l) { lastLocation = l; dirty = true; }
    public void setHealth(double h) { health = h; dirty = true; }
    public void setHunger(int h) { hunger = h; dirty = true; }
    public void setSaturation(float s) { saturation = s; dirty = true; }
    public void setExperience(long x) { experience = x; dirty = true; }
    public void setHome(BotLocation h) { home = h; dirty = true; }
    public void setGoals(List<String> g) { goals = List.copyOf(g); dirty = true; }
    public void setSkinSpec(String s) { skinSpec = s; dirty = true; }
    public void incrementDeaths() { deathCount++; dirty = true; }
    public void putExtra(String k, String v) { extra.put(k, v); dirty = true; }
    public void removeExtra(String k) { if (extra.remove(k) != null) dirty = true; }

    public long extraLong(String key, long def) {
        String v = extra.get(key);
        if (v == null) return def;
        try { return Long.parseLong(v); } catch (NumberFormatException e) { return def; }
    }

    // runtime helpers
    public long outOfRangeSinceMs() { return outOfRangeSinceMs; }
    public void setOutOfRangeSinceMs(long t) { outOfRangeSinceMs = t; }
    public boolean worldMissingWarned() { return worldMissingWarned; }
    public void setWorldMissingWarned(boolean b) { worldMissingWarned = b; }

    /** Records an entity that vanished without a death event; true if this is a repeating loss (>=3 in 2 minutes). */
    public boolean recordUnexpectedLoss(long nowMs) {
        unexpectedLosses.addLast(nowMs);
        while (!unexpectedLosses.isEmpty() && nowMs - unexpectedLosses.peekFirst() > 120_000) unexpectedLosses.removeFirst();
        return unexpectedLosses.size() >= 3;
    }
}
