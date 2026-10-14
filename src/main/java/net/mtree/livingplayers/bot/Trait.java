package net.mtree.livingplayers.bot;

import java.util.Locale;

/** Personality dimensions. Every trait is a value in [0, 1]. */
public enum Trait {
    AGGRESSION, FRIENDLINESS, COURAGE, CURIOSITY, SOCIABILITY, CAUTION,
    RESOURCEFULNESS, BUILDING_PREFERENCE, COMBAT_PREFERENCE, COOPERATION, RISK_TOLERANCE;

    public String key() { return name().toLowerCase(Locale.ROOT); }

    public static Trait fromKey(String key) {
        if (key == null) return null;
        try { return valueOf(key.trim().toUpperCase(Locale.ROOT).replace('-', '_')); }
        catch (IllegalArgumentException e) { return null; }
    }
}
