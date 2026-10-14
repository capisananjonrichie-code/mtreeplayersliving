package net.mtree.livingplayers.bot;

import java.util.Locale;

public enum CombatStyle {
    AGGRESSIVE_SWORD_RUSHER,
    DEFENSIVE_AXE_FIGHTER,
    SHIELD_SPECIALIST,
    RANGED_SPECIALIST,
    MACE_WIND_CHARGE_FIGHTER,
    OPPORTUNISTIC_AMBUSHER,
    DEFENSIVE_SURVIVALIST,
    TEAM_FIGHTER;

    public static CombatStyle parse(String s, CombatStyle fallback) {
        if (s == null) return fallback;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
