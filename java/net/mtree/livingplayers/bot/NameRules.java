package net.mtree.livingplayers.bot;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/** Name validation and unique-name generation. Names are display data; UUIDs are the identity. */
public final class NameRules {
    private NameRules() {}

    private static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    public static boolean isValid(String name) { return name != null && VALID.matcher(name).matches(); }

    public static String normalize(String name) { return name == null ? "" : name.toLowerCase(Locale.ROOT); }

    /**
     * Picks an unused name from the pool; falls back to numbered variants, then a random tag.
     * @param taken names already in use (any case) - bots AND known real players
     */
    public static String generate(Random rnd, List<String> pool, Collection<String> taken) {
        Set<String> used = new HashSet<>();
        for (String s : taken) used.add(normalize(s));
        List<String> valid = pool == null ? List.of() : pool.stream().filter(NameRules::isValid).toList();
        if (!valid.isEmpty()) {
            List<String> free = valid.stream().filter(n -> !used.contains(normalize(n))).toList();
            if (!free.isEmpty()) return free.get(rnd.nextInt(free.size()));
            for (int i = 0; i < 200; i++) {
                String base = valid.get(rnd.nextInt(valid.size()));
                String suffix = String.valueOf(2 + rnd.nextInt(998));
                String cand = base.substring(0, Math.min(base.length(), 16 - suffix.length())) + suffix;
                if (isValid(cand) && !used.contains(normalize(cand))) return cand;
            }
        }
        for (int i = 0; i < 1000; i++) {
            String cand = "Bot_" + Integer.toHexString(rnd.nextInt(0xFFFFF) + 0x10000);
            if (!used.contains(normalize(cand))) return cand;
        }
        throw new IllegalStateException("could not generate a unique bot name");
    }
}
