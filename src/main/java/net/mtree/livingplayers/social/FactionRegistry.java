package net.mtree.livingplayers.social;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bot factions (groups). One faction per bot; empty factions dissolve. Real players are never members. Pure. */
public final class FactionRegistry {
    public record Faction(UUID id, String name, UUID founder, Set<UUID> members, long createdAt) {}

    public record Result(boolean ok, String message) {}

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9 _'-]{3,24}$");

    private final int maxSize;
    private final Map<UUID, Faction> factions = new LinkedHashMap<>();
    private final Map<UUID, UUID> factionOfBot = new LinkedHashMap<>();

    public FactionRegistry(int maxSize) { this.maxSize = Math.max(2, maxSize); }

    public static boolean validName(String n) { return n != null && NAME.matcher(n).matches(); }

    public Result create(String name, UUID founder, long now) {
        if (!validName(name)) return new Result(false, "invalid faction name");
        if (factionOfBot.containsKey(founder)) return new Result(false, "already in a faction");
        if (factions.values().stream().anyMatch(f -> f.name().equalsIgnoreCase(name))) return new Result(false, "name taken");
        Faction f = new Faction(UUID.randomUUID(), name, founder, new LinkedHashSet<>(Set.of(founder)), now);
        factions.put(f.id(), f);
        factionOfBot.put(founder, f.id());
        return new Result(true, f.id().toString());
    }

    public Result join(UUID factionId, UUID bot) {
        Faction f = factions.get(factionId);
        if (f == null) return new Result(false, "no such faction");
        if (factionOfBot.containsKey(bot)) return new Result(false, "already in a faction");
        if (f.members().size() >= maxSize) return new Result(false, "faction is full");
        f.members().add(bot);
        factionOfBot.put(bot, factionId);
        return new Result(true, "joined");
    }

    public Result leave(UUID bot) {
        UUID id = factionOfBot.remove(bot);
        if (id == null) return new Result(false, "not in a faction");
        Faction f = factions.get(id);
        f.members().remove(bot);
        if (f.members().isEmpty()) factions.remove(id);
        else if (f.founder().equals(bot)) { // hand the banner to the next member
            Faction n = new Faction(f.id(), f.name(), f.members().iterator().next(), f.members(), f.createdAt());
            factions.put(id, n);
        }
        return new Result(true, "left");
    }

    public Optional<Faction> of(UUID bot) { return Optional.ofNullable(factionOfBot.get(bot)).map(factions::get); }
    public boolean sameFaction(UUID a, UUID b) { UUID fa = factionOfBot.get(a); return fa != null && fa.equals(factionOfBot.get(b)); }
    public List<Faction> all() { return new ArrayList<>(factions.values()); }
    public Optional<Faction> byName(String n) { return factions.values().stream().filter(f -> f.name().equalsIgnoreCase(n)).findFirst(); }

    public void load(List<Faction> saved) {
        factions.clear(); factionOfBot.clear();
        for (Faction f : saved) { factions.put(f.id(), f); for (UUID m : f.members()) factionOfBot.put(m, f.id()); }
    }

    /** Called when a bot identity is deleted. */
    public void forget(UUID bot) { if (factionOfBot.containsKey(bot)) leave(bot); }

    public static String normalize(String n) { return n.toLowerCase(Locale.ROOT).trim(); }
}
