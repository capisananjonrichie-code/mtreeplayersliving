package net.mtree.livingplayers.combat;

/** Vanilla attack-cooldown and critical-hit rules, so bots hit exactly as hard as a player could. Pure. */
public final class AttackCooldown {
    private AttackCooldown() {}

    public static double fullChargeTicks(double attackSpeed) { return 20.0 / attackSpeed; }

    /** 0..1 charge after {@code ticksSinceLastAttack} ticks. */
    public static double charge(double ticksSinceLastAttack, double attackSpeed) {
        return Math.max(0.0, Math.min(1.0, (ticksSinceLastAttack + 0.5) / fullChargeTicks(attackSpeed)));
    }

    public static double damageScale(double charge) { return 0.2 + charge * charge * 0.8; }

    /** Critical hit: nearly full charge, falling, airborne, not sprinting, not in water. */
    public static boolean canCrit(double charge, boolean falling, boolean onGround, boolean sprinting, boolean inWater) {
        return charge > 0.9 && falling && !onGround && !sprinting && !inWater;
    }

    public static double damage(double weaponDamage, double charge, boolean crit) {
        double d = weaponDamage * damageScale(charge);
        return crit ? d * 1.5 : d;
    }
}
