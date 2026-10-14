package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.CombatStyle;

import java.util.List;
import java.util.Map;

/** Pure gear scoring by material name. Armour slot index: 0 boots, 1 leggings, 2 chestplate, 3 helmet. */
public final class EquipmentEvaluator {
    private EquipmentEvaluator() {}

    private static final Map<String, int[]> POINTS = Map.of(
        "LEATHER", new int[] {1, 2, 3, 1}, "GOLDEN", new int[] {1, 3, 5, 2}, "CHAINMAIL", new int[] {1, 4, 5, 2},
        "IRON", new int[] {2, 5, 6, 2}, "DIAMOND", new int[] {3, 6, 8, 3}, "NETHERITE", new int[] {3, 6, 8, 3});

    public static int armorSlot(String m) {
        if (m == null) return -1;
        if (m.endsWith("_BOOTS")) return 0;
        if (m.endsWith("_LEGGINGS")) return 1;
        if (m.endsWith("_CHESTPLATE")) return 2;
        if (m.endsWith("_HELMET")) return 3;
        return -1;
    }

    /** Armour points plus a bonus for toughness; 0 for non-armour or empty. */
    public static double armorScore(String m) {
        int slot = armorSlot(m);
        if (slot < 0) return 0;
        if (m.equals("TURTLE_HELMET")) return 2;
        String tier = m.substring(0, m.indexOf('_'));
        int[] p = POINTS.get(tier);
        if (p == null) return 0;
        double toughness = tier.equals("NETHERITE") ? 3 : tier.equals("DIAMOND") ? 2 : 0;
        return p[slot] + toughness * 0.5 + (tier.equals("NETHERITE") ? 0.25 : 0);
    }

    public static double weaponScore(String m, CombatStyle style) {
        WeaponStats w = WeaponStats.of(m);
        if (!w.isWeapon()) return 0;
        double mult = switch (style) {
            case AGGRESSIVE_SWORD_RUSHER -> w.kind().equals("SWORD") ? 1.25 : 1.0;
            case DEFENSIVE_AXE_FIGHTER -> w.kind().equals("AXE") ? 1.4 : 1.0;
            case SHIELD_SPECIALIST -> w.kind().equals("SWORD") ? 1.1 : 1.0;
            case MACE_WIND_CHARGE_FIGHTER -> w.kind().equals("MACE") ? 1.3 : 1.0;
            default -> 1.0;
        };
        return w.dps() * mult;
    }

    /** Index into {@code names} (null/empty entries allowed) of the best melee weapon, or -1 if none beats a fist. */
    public static int bestWeaponIndex(List<String> names, CombatStyle style) {
        int best = -1;
        double bestScore = 0;
        for (int i = 0; i < names.size(); i++) {
            double s = weaponScore(names.get(i), style);
            if (s > bestScore) { bestScore = s; best = i; }
        }
        return best;
    }
}
