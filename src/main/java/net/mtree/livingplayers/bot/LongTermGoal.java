package net.mtree.livingplayers.bot;

import java.util.Locale;

/**
 * Long-term objectives. In Stage 1 these are chosen from personality and persisted;
 * the GoalSelector that turns them into tasks arrives in a later stage.
 */
public enum LongTermGoal {
    BECOME_PVP_FIGHTER, ESTABLISH_VILLAGE, BUILD_HIDDEN_BASE, COLLECT_DIAMONDS,
    EXPLORE_ANCIENT_STRUCTURES, BECOME_TRADER, CREATE_FARM, DEFEND_TERRITORY,
    EXPLORE_CAVES, COLLECT_RARE_ITEMS, FORM_GROUP, HUNT_DANGEROUS_MOBS;

    public static LongTermGoal parse(String s) {
        if (s == null) return null;
        try { return valueOf(s.trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { return null; }
    }
}
