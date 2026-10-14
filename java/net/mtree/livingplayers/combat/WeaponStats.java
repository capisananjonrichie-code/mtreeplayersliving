package net.mtree.livingplayers.combat;

import java.util.Map;

/** Vanilla Java Edition melee numbers (damage, attack speed) by material name. Pure. */
public record WeaponStats(String kind, double damage, double attackSpeed) {
    public static final WeaponStats FIST = new WeaponStats("FIST", 1.0, 4.0);

    private static final Map<String, Double> SWORD = Map.of("WOODEN", 4.0, "GOLDEN", 4.0, "STONE", 5.0, "IRON", 6.0, "DIAMOND", 7.0, "NETHERITE", 8.0);
    private static final Map<String, Double> AXE = Map.of("WOODEN", 7.0, "GOLDEN", 7.0, "STONE", 9.0, "IRON", 9.0, "DIAMOND", 9.0, "NETHERITE", 10.0);
    private static final Map<String, Double> AXE_SPEED = Map.of("WOODEN", 0.8, "GOLDEN", 1.0, "STONE", 0.8, "IRON", 0.9, "DIAMOND", 1.0, "NETHERITE", 1.0);

    public double dps() { return damage * attackSpeed; }

    public boolean isWeapon() { return !kind.equals("FIST"); }

    /** Stats for the item named by a Material name; any non-weapon (or null) is a fist. */
    public static WeaponStats of(String material) {
        if (material == null) return FIST;
        String tier = material.contains("_") ? material.substring(0, material.indexOf('_')) : "";
        if (material.endsWith("_SWORD") && SWORD.containsKey(tier)) return new WeaponStats("SWORD", SWORD.get(tier), 1.6);
        if (material.endsWith("_AXE") && AXE.containsKey(tier)) return new WeaponStats("AXE", AXE.get(tier), AXE_SPEED.get(tier));
        if (material.equals("MACE")) return new WeaponStats("MACE", 6.0, 0.6);
        if (material.equals("TRIDENT")) return new WeaponStats("TRIDENT", 9.0, 1.1);
        return FIST;
    }
}
