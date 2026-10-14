package net.mtree.livingplayers.social;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

/** A bot's bounded collection of rumors, with lossy retelling. Pure. */
public final class RumorBook {
    public static final int MIN_SHAREABLE_CONFIDENCE = 25;

    private final int max;
    private final List<Rumor> rumors = new ArrayList<>();

    public RumorBook(int max) { this.max = Math.max(1, max); }

    /** Coarse description of an offset (dx east, dz south) - buckets and compass words only, no digits. */
    public static String where(double dx, double dz) {
        double d = Math.hypot(dx, dz);
        if (d < 48) return "right around here";
        String dir = compass(dx, dz);
        if (d < 160) return "not far to the " + dir;
        if (d < 400) return "a good walk away to the " + dir;
        return "far off to the " + dir;
    }

    static String compass(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north, 90 = east
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.floorMod(Math.round(deg / 45.0), 8L)];
    }

    public void add(Rumor r) {
        for (int i = 0; i < rumors.size(); i++) {
            Rumor o = rumors.get(i);
            if (o.kind().equals(r.kind()) && o.subject().equalsIgnoreCase(r.subject())) {
                if (r.confidence() > o.confidence()) rumors.set(i, r);
                return;
            }
        }
        rumors.add(r);
        while (rumors.size() > max) rumors.remove(rumors.stream().min(java.util.Comparator.comparingInt(Rumor::confidence)).orElseThrow());
    }

    public List<Rumor> all() { return List.copyOf(rumors); }
    public int size() { return rumors.size(); }
    public void load(List<Rumor> saved) { rumors.clear(); rumors.addAll(saved); }

    /** A rumor this bot is willing to repeat, weighted by how sure it is. */
    public Optional<Rumor> pickToShare(Random rnd) {
        List<Rumor> ok = rumors.stream().filter(r -> r.confidence() >= MIN_SHAREABLE_CONFIDENCE).toList();
        if (ok.isEmpty()) return Optional.empty();
        int total = ok.stream().mapToInt(Rumor::confidence).sum(), roll = rnd.nextInt(total), acc = 0;
        for (Rumor r : ok) { acc += r.confidence(); if (roll < acc) return Optional.of(r); }
        return Optional.of(ok.get(ok.size() - 1));
    }

    /** What the listener ends up with: less sure, one hop further, and sometimes the place gets muddled. */
    public static Rumor retell(Rumor r, Random rnd) {
        int conf = Math.max(0, r.confidence() - (10 + rnd.nextInt(16)));
        String where = r.where();
        if (conf < 50 && rnd.nextDouble() < 0.5) where = muddle(where, rnd);
        return new Rumor(UUID.randomUUID(), r.kind(), r.subject(), where, conf, r.hops() + 1, r.createdAt());
    }

    private static String muddle(String where, Random rnd) {
        String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        for (String d : dirs) if (where.endsWith(d)) return where.substring(0, where.length() - d.length()) + dirs[rnd.nextInt(dirs.length)];
        return where;
    }
}
