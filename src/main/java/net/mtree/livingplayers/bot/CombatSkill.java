package net.mtree.livingplayers.bot;

import java.util.Locale;

public enum CombatSkill {
    BEGINNER, INTERMEDIATE, ADVANCED, EXPERT;

    public static CombatSkill parse(String s, CombatSkill fallback) {
        if (s == null) return fallback;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return fallback; }
    }
}
