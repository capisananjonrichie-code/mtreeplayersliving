package net.mtree.livingplayers.social;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Bounded memory of SIGNIFICANT events only. When full, the least important/oldest memory is forgotten (importance halves
 * every 14 days), so a bot remembers who attacked it long after it forgets a greeting.
 */
public final class MemoryStore {
    public record Memory(String kind, UUID subject, String text, int importance, long at) {}

    private static final double HALF_LIFE_MS = 14.0 * 24 * 3600 * 1000;
    private final int max;
    private final List<Memory> items = new ArrayList<>();

    public MemoryStore(int max) { this.max = Math.max(1, max); }

    public void add(Memory m) {
        // the same kind of event about the same subject within a minute is one memory, not many
        for (int i = 0; i < items.size(); i++) {
            Memory o = items.get(i);
            if (o.kind().equals(m.kind()) && java.util.Objects.equals(o.subject(), m.subject()) && Math.abs(m.at() - o.at()) < 60_000) {
                items.set(i, new Memory(o.kind(), o.subject(), m.text(), Math.max(o.importance(), m.importance()), Math.max(o.at(), m.at())));
                return;
            }
        }
        items.add(m);
        while (items.size() > max) forgetWeakest(m.at());
    }

    private void forgetWeakest(long now) {
        Memory weakest = items.stream().min(Comparator.comparingDouble(x -> strength(x, now))).orElse(null);
        items.remove(weakest);
    }

    static double strength(Memory m, long now) { return m.importance() * Math.pow(0.5, Math.max(0, now - m.at()) / HALF_LIFE_MS); }

    public List<Memory> all() { return List.copyOf(items); }

    public List<Memory> about(UUID subject) { return items.stream().filter(m -> subject.equals(m.subject())).toList(); }

    public boolean has(String kind, UUID subject) { return items.stream().anyMatch(m -> m.kind().equals(kind) && subject.equals(m.subject())); }

    public int size() { return items.size(); }

    public void load(List<Memory> saved) { items.clear(); items.addAll(saved); }
}
