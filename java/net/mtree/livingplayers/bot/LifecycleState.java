package net.mtree.livingplayers.bot;

import java.util.Locale;

/**
 * Lifecycle of a persistent bot identity. The identity always exists in the database;
 * only {@link #ACTIVE} bots have a live world entity.
 *
 * <ul>
 *   <li>ACTIVE     - entity exists in a loaded chunk.</li>
 *   <li>INACTIVE   - deliberately despawned (director/command). Kept in DB, not auto-activated.</li>
 *   <li>UNLOADED   - was active, but its chunk/world is not loaded (restart, chunk unload, missing world).
 *                    Reactivated automatically once its chunk is loaded again.</li>
 *   <li>DEAD       - died, respawn disabled.</li>
 *   <li>RESPAWNING - died, waiting for its respawn timer.</li>
 *   <li>DISABLED   - administratively disabled; never touched by automation.</li>
 * </ul>
 */
public enum LifecycleState {
    ACTIVE, INACTIVE, UNLOADED, DEAD, RESPAWNING, DISABLED;

    public boolean canTransitionTo(LifecycleState next) {
        if (next == this) return true;
        return switch (this) {
            case ACTIVE -> true; // despawn, unload, die, or be disabled
            case INACTIVE -> next == ACTIVE || next == DISABLED;
            case UNLOADED -> next == ACTIVE || next == INACTIVE || next == DISABLED;
            case DEAD -> next == RESPAWNING || next == INACTIVE || next == DISABLED;
            case RESPAWNING -> next == ACTIVE || next == DEAD || next == INACTIVE || next == DISABLED;
            case DISABLED -> next == INACTIVE;
        };
    }

    /** True if a live world entity is expected for this state. */
    public boolean expectsEntity() { return this == ACTIVE; }

    /** True if automation should bring the bot back as soon as its chunk is loaded. */
    public boolean autoReactivates() { return this == UNLOADED; }

    public static LifecycleState parse(String s, LifecycleState fallback) {
        if (s == null) return fallback;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
