package net.mtree.livingplayers.combat;

import net.mtree.livingplayers.bot.CombatStyle;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CombatMathTest {
    @Test void weaponNumbersMatchVanilla() {
        assertEquals(7.0, WeaponStats.of("DIAMOND_SWORD").damage());
        assertEquals(1.6, WeaponStats.of("IRON_SWORD").attackSpeed());
        assertEquals(10.0, WeaponStats.of("NETHERITE_AXE").damage());
        assertEquals(0.8, WeaponStats.of("STONE_AXE").attackSpeed());
        assertEquals(WeaponStats.FIST, WeaponStats.of("DIRT"));
        assertEquals(WeaponStats.FIST, WeaponStats.of(null));
        assertFalse(WeaponStats.of("OAK_LOG").isWeapon());
    }
    @Test void cooldownScalesDamageAndNeverExceedsFull() {
        assertEquals(20.0 / 1.6, AttackCooldown.fullChargeTicks(1.6), 1e-9);
        assertTrue(AttackCooldown.charge(0, 1.6) < 0.1);
        assertEquals(1.0, AttackCooldown.charge(100, 1.6));
        assertEquals(1.0, AttackCooldown.damageScale(1.0), 1e-9);
        assertEquals(0.2, AttackCooldown.damageScale(0.0), 1e-9);
        assertTrue(AttackCooldown.damage(7, 0.3, false) < AttackCooldown.damage(7, 1.0, false) / 2, "spam-clicking must be weak");
    }
    @Test void critRules() {
        assertTrue(AttackCooldown.canCrit(1.0, true, false, false, false));
        assertFalse(AttackCooldown.canCrit(0.5, true, false, false, false), "needs charge");
        assertFalse(AttackCooldown.canCrit(1.0, true, true, false, false), "needs to be airborne");
        assertFalse(AttackCooldown.canCrit(1.0, true, false, true, false), "sprinting cancels crits");
        assertFalse(AttackCooldown.canCrit(1.0, true, false, false, true), "water cancels crits");
        assertEquals(10.5, AttackCooldown.damage(7, 1.0, true), 1e-9);
    }
    @Test void armourSlotsAndScores() {
        assertEquals(0, EquipmentEvaluator.armorSlot("IRON_BOOTS"));
        assertEquals(3, EquipmentEvaluator.armorSlot("DIAMOND_HELMET"));
        assertEquals(-1, EquipmentEvaluator.armorSlot("IRON_SWORD"));
        assertTrue(EquipmentEvaluator.armorScore("DIAMOND_CHESTPLATE") > EquipmentEvaluator.armorScore("IRON_CHESTPLATE"));
        assertTrue(EquipmentEvaluator.armorScore("NETHERITE_CHESTPLATE") > EquipmentEvaluator.armorScore("DIAMOND_CHESTPLATE"));
        assertTrue(EquipmentEvaluator.armorScore("IRON_CHESTPLATE") > EquipmentEvaluator.armorScore("LEATHER_CHESTPLATE"));
        assertEquals(0, EquipmentEvaluator.armorScore("STONE"));
    }
    @Test void weaponChoiceFollowsDpsAndStyle() {
        List<String> inv = Arrays.asList("STONE_SWORD", null, "DIAMOND_AXE", "DIRT");
        assertEquals(2, EquipmentEvaluator.bestWeaponIndex(inv, CombatStyle.DEFENSIVE_AXE_FIGHTER));
        assertEquals(0, EquipmentEvaluator.bestWeaponIndex(Arrays.asList("STONE_SWORD", "WOODEN_AXE"), CombatStyle.AGGRESSIVE_SWORD_RUSHER));
        assertEquals(-1, EquipmentEvaluator.bestWeaponIndex(Arrays.asList("DIRT", null), CombatStyle.TEAM_FIGHTER), "no weapon beats fists");
        assertEquals(1, EquipmentEvaluator.bestWeaponIndex(Arrays.asList("WOODEN_SWORD", "DIAMOND_SWORD"), CombatStyle.TEAM_FIGHTER));
    }
    @Test void foodChoiceAvoidsWasteAndRejectsInedible() {
        assertEquals(-1, FoodTable.bestFoodIndex(Arrays.asList("DIRT", null, "ROTTEN_FLESH", "SPIDER_EYE"), 10));
        List<String> inv = Arrays.asList("COOKED_BEEF", "APPLE", "BREAD");
        assertEquals(1, FoodTable.bestFoodIndex(inv, 17), "missing 3 -> smallest food that covers it is the apple");
        assertEquals(0, FoodTable.bestFoodIndex(inv, 8), "missing 12 -> biggest food");
        assertTrue(FoodTable.of("COOKED_BEEF").isPresent());
        assertTrue(FoodTable.of("ROTTEN_FLESH").isEmpty());
    }
}
