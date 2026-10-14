package net.mtree.livingplayers.bot;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable set of trait values, each clamped to [0, 1]. Missing traits default to 0.5. */
public final class Personality {
    private final EnumMap<Trait, Double> values = new EnumMap<>(Trait.class);

    public Personality(Map<Trait, Double> input) {
        for (Trait t : Trait.values()) {
            Double v = input == null ? null : input.get(t);
            values.put(t, v == null || v.isNaN() ? 0.5 : Math.max(0.0, Math.min(1.0, v)));
        }
    }

    public double get(Trait t) { return values.get(t); }

    public Map<Trait, Double> asMap() { return Collections.unmodifiableMap(values); }

    @Override public boolean equals(Object o) { return o instanceof Personality p && values.equals(p.values); }
    @Override public int hashCode() { return Objects.hash(values); }
    @Override public String toString() { return "Personality" + values; }
}
