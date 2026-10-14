package net.mtree.livingplayers.combat;

/**
 * Vanilla hunger/regeneration/starvation so healing is never free: regeneration costs exhaustion, exhaustion drains
 * saturation then hunger, and at 0 hunger bots starve (to death on Hard). Pure; one call = one tick.
 */
public final class HungerModel {
    private HungerModel() {}

    public record State(int hunger, float saturation, float exhaustion, int timer) {
        public static State of(int hunger, float saturation) { return new State(hunger, saturation, 0f, 0); }
    }

    /** @param healthDelta positive = heal, negative = starvation damage this tick */
    public record Result(State state, double healthDelta) {}

    public static State addExhaustion(State s, float amount) {
        return new State(s.hunger(), s.saturation(), Math.min(40f, s.exhaustion() + amount), s.timer());
    }

    public static State eat(State s, int nutrition, float saturation) {
        int h = Math.min(20, s.hunger() + nutrition);
        float sat = Math.min(h, s.saturation() + saturation); // saturation can never exceed hunger
        return new State(h, sat, s.exhaustion(), s.timer());
    }

    public static Result tick(State s, double health, double maxHealth, boolean hardDifficulty) {
        int hunger = s.hunger();
        float sat = s.saturation(), exh = s.exhaustion();
        int timer = s.timer();
        double delta = 0;

        if (exh > 4f) {
            exh -= 4f;
            if (sat > 0f) sat = Math.max(sat - 1f, 0f);
            else if (hunger > 0) hunger--;
        }

        boolean canHeal = health > 0 && health < maxHealth;
        if (canHeal && hunger >= 20 && sat > 0f) {
            if (++timer >= 10) {
                float f = Math.min(sat, 6f);
                delta = Math.min(f / 6f, maxHealth - health);
                exh = Math.min(40f, exh + f);
                timer = 0;
            }
        } else if (canHeal && hunger >= 18) {
            if (++timer >= 80) { delta = Math.min(1.0, maxHealth - health); exh = Math.min(40f, exh + 6f); timer = 0; }
        } else if (hunger <= 0) {
            if (++timer >= 80) {
                if (hardDifficulty || health > 1.0) delta = -1.0; // easier difficulties never kill by starvation
                timer = 0;
            }
        } else {
            timer = 0;
        }
        return new Result(new State(hunger, sat, exh, timer), delta);
    }
}
