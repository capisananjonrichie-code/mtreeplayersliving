package net.mtree.livingplayers.bot;

import java.util.Map;

/** An archetype: selection weight plus an optional [min,max] range per trait. */
public record PersonalityTemplate(String id, double weight, Map<Trait, Range> ranges) {
    public record Range(double min, double max) {
        public Range {
            if (min > max) { double t = min; min = max; max = t; }
            min = Math.max(0, min);
            max = Math.min(1, max);
        }
    }
}
