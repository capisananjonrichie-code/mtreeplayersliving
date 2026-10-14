package net.mtree.livingplayers.command;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotLocation;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.LifecycleState;
import net.mtree.livingplayers.bot.NameRules;
import net.mtree.livingplayers.social.SocialService;
import net.mtree.livingplayers.social.SocialState;
import net.mtree.livingplayers.building.BlueprintManager;
import net.mtree.livingplayers.building.BuildingAI;
import net.mtree.livingplayers.combat.BotEquipmentService;
import net.mtree.livingplayers.core.Msg;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.director.BotPopulationDirector;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.nav.BotNavigator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** /mtreebots. Only subcommands with working behaviour exist; each checks its own permission. */
public final class BotCommand implements TabExecutor {
    private record Sub(String name, String permission, String usage, String help) {}

    private static final List<Sub> SUBS = List.of(
        new Sub("help", null, "help", "Show this list"),
        new Sub("spawn", "mtreebots.spawn", "spawn <name>", "Spawn a bot at your position (creates it if the name is new)"),
        new Sub("spawnrandom", "mtreebots.spawn", "spawnrandom <1-10>", "Spawn bots at safe spots near players"),
        new Sub("despawn", "mtreebots.spawn", "despawn <name>", "Remove the entity but keep the identity (INACTIVE)"),
        new Sub("remove", "mtreebots.manage", "remove <name> confirm", "PERMANENTLY delete a bot (drops its items)"),
        new Sub("list", "mtreebots.manage", "list", "List all bots"),
        new Sub("info", "mtreebots.manage", "info <name>", "Show a bot's details"),
        new Sub("tp", "mtreebots.manage", "tp <name>", "Teleport to a bot"),
        new Sub("home", "mtreebots.manage", "home <name> [set|clear|show]", "Set the bot's home to your position"),
        new Sub("goto", "mtreebots.manage", "goto <name>", "Make a bot walk to your position"),
        new Sub("follow", "mtreebots.manage", "follow <name>", "Make a bot follow you"),
        new Sub("stop", "mtreebots.manage", "stop <name>", "Stop a bot's movement and pause wandering"),
        new Sub("resume", "mtreebots.manage", "resume <name>", "Resume a bot's autonomous wandering"),
        new Sub("give", "mtreebots.manage", "give <name>", "Hand the item in your main hand to a bot"),
        new Sub("inv", "mtreebots.manage", "inv <name>", "Show a bot's inventory"),
        new Sub("faction", "mtreebots.manage", "faction list", "List bot factions"),
        new Sub("memory", "mtreebots.manage", "memory <name>", "Show what a bot remembers (feelings, memories, rumors)"),
        new Sub("build", "mtreebots.manage", "build <name> <blueprint|status|cancel>", "Start, inspect or cancel a bot's construction project"),
        new Sub("equip", "mtreebots.manage", "equip <name>", "Make a bot wear/hold the best gear it already owns"),
        new Sub("enable", "mtreebots.manage", "enable <name>", "Re-enable a disabled bot"),
        new Sub("disable", "mtreebots.manage", "disable <name>", "Disable a bot (never touched by automation)"),
        new Sub("population", "mtreebots.manage", "population", "Population director status"),
        new Sub("save", "mtreebots.manage", "save", "Write all bots to the database now"),
        new Sub("reload", "mtreebots.reload", "reload", "Reload the configuration files"),
        new Sub("debug", "mtreebots.debug", "debug [name|toggle]", "Diagnostics"));

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final BotPopulationDirector director;
    private final BotDebugCommand debug;
    private final BotNavigator navigator;
    private final BotEquipmentService equipment;
    private final BuildingAI building;
    private final BlueprintManager blueprints;
    private final SocialService social;
    private final Runnable reload;

    public BotCommand(Plugin plugin, Supplier<Settings> settings, BotManager manager, BotEntityAdapter adapter,
                      BotPopulationDirector director, BotDebugCommand debug, BotNavigator navigator, BotEquipmentService equipment, BuildingAI building, BlueprintManager blueprints, SocialService social, Runnable reload) {
        this.plugin = plugin; this.settings = settings; this.manager = manager; this.adapter = adapter;
        this.director = director; this.debug = debug; this.navigator = navigator; this.equipment = equipment; this.building = building; this.blueprints = blueprints; this.social = social; this.reload = reload;
    }

    private static boolean can(CommandSender s, Sub sub) { return sub.permission() == null || s.hasPermission(sub.permission()); }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) { help(s, label); return true; }
        Sub sub = SUBS.stream().filter(x -> x.name().equals(a[0].toLowerCase(Locale.ROOT))).findFirst().orElse(null);
        if (sub == null) { Msg.err(s, "Unknown subcommand. Try /" + label + " help"); return true; }
        if (!can(s, sub)) { Msg.err(s, "You don't have permission (" + sub.permission() + ")."); return true; }
        if (!manager.isReady()) { Msg.err(s, "Bots are still loading from the database. Try again in a moment."); return true; }
        try {
            switch (sub.name()) {
                case "spawn" -> spawn(s, a, sub);
                case "spawnrandom" -> spawnRandom(s, a, sub);
                case "despawn" -> withBot(s, a, sub, b -> reply(s, manager.deactivate(b, LifecycleState.INACTIVE)));
                case "remove" -> remove(s, a, sub);
                case "list" -> list(s);
                case "info" -> withBot(s, a, sub, b -> info(s, b));
                case "tp" -> tp(s, a, sub);
                case "home" -> home(s, a, sub);
                case "goto" -> walkTo(s, a, sub);
                case "follow" -> followMe(s, a, sub);
                case "stop" -> withBot(s, a, sub, b -> { navigator.stop(b); Msg.ok(s, b.name() + " stopped (wandering paused; /mtreebots resume " + b.name() + ")."); });
                case "resume" -> withBot(s, a, sub, b -> { navigator.resume(b); Msg.ok(s, b.name() + " will wander again."); });
                case "faction" -> factions(s, a, sub);
                case "memory" -> withBot(s, a, sub, b -> memory(s, b));
                case "build" -> withBot(s, a, sub, b -> {
                    if (a.length < 3) { Msg.info(s, "Blueprints: " + blueprints.names() + " | " + building.describe(b.id())); usage(s, sub); return; }
                    switch (a[2].toLowerCase(Locale.ROOT)) {
                        case "status" -> Msg.info(s, building.describe(b.id()));
                        case "cancel" -> reply(s, building.cancel(b));
                        default -> reply(s, building.start(b, a[2]));
                    }
                });
                case "equip" -> withBot(s, a, sub, b -> Msg.ok(s, equipment.refresh(b) ? b.name() + " re-equipped." : b.name() + " already has its best gear equipped."));
                case "give" -> give(s, a, sub);
                case "inv" -> withBot(s, a, sub, b -> inv(s, b));
                case "enable" -> withBot(s, a, sub, b -> enable(s, b));
                case "disable" -> withBot(s, a, sub, b -> reply(s, manager.deactivate(b, LifecycleState.DISABLED)));
                case "population" -> population(s);
                case "save" -> save(s);
                case "reload" -> { reload.run(); Msg.ok(s, "Configuration reloaded. (persistence.backups-to-keep needs a restart.)"); }
                case "debug" -> debug.handle(s, a);
                default -> Msg.err(s, "Not implemented.");
            }
        } catch (RuntimeException e) {
            Msg.err(s, "Internal error: " + e.getClass().getSimpleName() + " (see console).");
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Command failed: /" + label + " " + String.join(" ", a), e);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        Msg.info(s, "MTREE Living Players - commands you can use:");
        boolean any = false;
        for (Sub sub : SUBS) if (can(s, sub)) { Msg.raw(s, "  §6/" + label + " " + sub.usage() + " §7- " + sub.help()); any = true; }
        if (!any) Msg.info(s, "(none - ask an admin for mtreebots.* permissions)");
    }

    private void usage(CommandSender s, Sub sub) { Msg.err(s, "Usage: /mtreebots " + sub.usage()); }

    private void reply(CommandSender s, BotManager.Result r) { if (r.ok()) Msg.ok(s, r.message()); else Msg.err(s, r.message()); }

    private void withBot(CommandSender s, String[] a, Sub sub, java.util.function.Consumer<BotIdentity> f) {
        if (a.length < 2) { usage(s, sub); return; }
        Optional<BotIdentity> b = manager.find(a[1]);
        if (b.isEmpty()) { Msg.err(s, "No bot named '" + a[1] + "'. See /mtreebots list"); return; }
        f.accept(b.get());
    }

    // ---------------------------------------------------------------- subcommands

    private void spawn(CommandSender s, String[] a, Sub sub) {
        if (!(s instanceof Player p)) { Msg.err(s, "Run this as a player (it spawns at your position)."); return; }
        if (a.length < 2) { usage(s, sub); return; }
        Location at = p.getLocation();
        Optional<BotIdentity> existing = manager.find(a[1]);
        if (existing.isPresent()) { reply(s, manager.activate(existing.get(), at)); return; }
        if (!NameRules.isValid(a[1])) { Msg.err(s, "Names must be 3-16 characters: letters, digits, underscore."); return; }
        if (manager.isNameTaken(a[1])) { Msg.err(s, "That name belongs to a real player. Pick another."); return; }
        if (manager.count() >= settings.get().population().maxPopulation()) { Msg.err(s, "population.max-population (" + settings.get().population().maxPopulation() + ") reached."); return; }
        BotIdentity b = manager.createBot(a[1], at);
        BotManager.Result r = manager.activate(b, at);
        if (r.ok()) Msg.ok(s, "Created and spawned " + b.name() + " (" + b.skill() + ", " + b.style() + ").");
        else Msg.err(s, "Created " + b.name() + " but could not spawn it: " + r.message() + " (it stays INACTIVE; try /mtreebots spawn " + b.name() + ")");
    }

    private void spawnRandom(CommandSender s, String[] a, Sub sub) {
        int n;
        try { n = a.length < 2 ? 1 : Integer.parseInt(a[1]); } catch (NumberFormatException e) { usage(s, sub); return; }
        if (n < 1 || n > 10) { Msg.err(s, "Amount must be 1-10."); return; }
        BotPopulationDirector.SpawnReport r = director.spawnRandom(n);
        if (!r.spawned().isEmpty()) Msg.ok(s, "Spawned " + r.spawned().size() + ": " + String.join(", ", r.spawned()));
        if (r.spawned().size() < n) Msg.err(s, "Could not spawn " + (n - r.spawned().size()) + ". Reasons: " + String.join("; ", r.failures().stream().distinct().toList()));
    }

    private void remove(CommandSender s, String[] a, Sub sub) {
        withBot(s, a, sub, b -> {
            if (a.length < 3 || !a[2].equalsIgnoreCase("confirm")) {
                Msg.err(s, "This permanently deletes " + b.name() + ". Run: /mtreebots remove " + b.name() + " confirm");
                return;
            }
            reply(s, manager.remove(b));
        });
    }

    private void list(CommandSender s) {
        if (manager.count() == 0) { Msg.info(s, "No bots yet. Use /mtreebots spawn <name> or spawnrandom."); return; }
        Msg.info(s, manager.count() + " bot(s), " + manager.activeCount() + " active:");
        int shown = 0;
        for (BotIdentity b : manager.all()) {
            if (shown++ >= 40) { Msg.info(s, "... (truncated)"); break; }
            BotLocation l = b.lastLocation();
            Msg.raw(s, "  " + color(b.state()) + b.name() + " §8[" + b.state() + "] §7" + b.skill() + "/" + b.style()
                + (l == null ? "" : String.format(" §8@ %s %d %d %d", l.worldName(), (int) l.x(), (int) l.y(), (int) l.z())));
        }
    }

    private static String color(LifecycleState st) {
        return switch (st) { case ACTIVE -> "§a"; case INACTIVE, UNLOADED -> "§7"; case RESPAWNING -> "§e"; case DEAD -> "§c"; case DISABLED -> "§8"; };
    }

    private void info(CommandSender s, BotIdentity b) {
        Msg.info(s, "§e" + b.name() + " §7(" + b.id() + ")");
        Msg.info(s, "State " + b.state() + " | skill " + b.skill() + " | style " + b.style() + " | deaths " + b.deathCount());
        Msg.info(s, String.format("HP %.1f | hunger %d | xp %d", b.health(), b.hunger(), b.experience()));
        Msg.info(s, "Goals: " + String.join(", ", b.goals()));
        Msg.info(s, "Home: " + (b.home() == null ? "none" : String.format("%s %d %d %d", b.home().worldName(), (int) b.home().x(), (int) b.home().y(), (int) b.home().z())));
        Msg.info(s, "Inventory: " + (b.inventory().isEmpty() ? "empty" : b.inventory().describe().size() + " kinds of items (/mtreebots inv " + b.name() + ")"));
    }

    private void tp(CommandSender s, String[] a, Sub sub) {
        if (!(s instanceof Player p)) { Msg.err(s, "Players only."); return; }
        withBot(s, a, sub, b -> {
            Location target = adapter.entity(b.id()).map(LivingEntity::getLocation).orElseGet(() -> manager.toLocation(b.lastLocation()));
            if (target == null) { Msg.err(s, b.name() + " has no resolvable location (world not loaded?)."); return; }
            p.teleportAsync(target);
            Msg.ok(s, "Teleporting to " + b.name() + (b.state() == LifecycleState.ACTIVE ? "." : " (last known position; bot is " + b.state() + ")."));
        });
    }

    private void home(CommandSender s, String[] a, Sub sub) {
        withBot(s, a, sub, b -> {
            String mode = a.length >= 3 ? a[2].toLowerCase(Locale.ROOT) : "set";
            switch (mode) {
                case "show" -> Msg.info(s, b.home() == null ? "No home set." : String.format("Home: %s %.0f %.0f %.0f", b.home().worldName(), b.home().x(), b.home().y(), b.home().z()));
                case "clear" -> { b.setHome(null); manager.saveAll(true); Msg.ok(s, "Home cleared."); }
                case "set" -> {
                    if (!(s instanceof Player p)) { Msg.err(s, "Run as a player to set home at your position."); return; }
                    b.setHome(BotManager.fromLocation(p.getLocation()));
                    manager.saveAll(true);
                    Msg.ok(s, b.name() + "'s home set to your position.");
                }
                default -> usage(s, sub);
            }
        });
    }

    private void walkTo(CommandSender s, String[] a, Sub sub) {
        if (!(s instanceof Player p)) { Msg.err(s, "Players only."); return; }
        withBot(s, a, sub, b -> reply(s, navigator.goTo(b, p.getLocation())));
    }

    private void followMe(CommandSender s, String[] a, Sub sub) {
        if (!(s instanceof Player p)) { Msg.err(s, "Players only."); return; }
        withBot(s, a, sub, b -> reply(s, navigator.follow(b, p)));
    }

    private void give(CommandSender s, String[] a, Sub sub) {
        if (!(s instanceof Player p)) { Msg.err(s, "Players only."); return; }
        withBot(s, a, sub, b -> {
            ItemStack hand = p.getInventory().getItemInMainHand();
            if (hand.getType().isAir() || hand.getAmount() <= 0) { Msg.err(s, "Hold an item in your main hand."); return; }
            int before = hand.getAmount();
            ItemStack leftover = b.inventory().add(hand.clone());
            int moved = before - (leftover == null ? 0 : leftover.getAmount());
            if (moved <= 0) { Msg.err(s, b.name() + "'s inventory is full."); return; }
            p.getInventory().setItemInMainHand(leftover == null ? new ItemStack(Material.AIR) : leftover);
            b.markDirty();
            adapter.pushState(b);
            manager.saveAll(true);
            social.onGift(b, p);
            Msg.ok(s, "Gave " + moved + "x " + hand.getType().name() + " to " + b.name() + (leftover != null ? " (inventory full, kept the rest)." : "."));
        });
    }

    private void factions(CommandSender s, String[] a, Sub sub) {
        if (a.length < 2 || !a[1].equalsIgnoreCase("list")) { usage(s, sub); return; }
        var all = social.state().factions.all();
        if (all.isEmpty()) { Msg.info(s, "No factions yet. Bots form them on their own once they like each other enough."); return; }
        for (var f : all) {
            StringBuilder names = new StringBuilder();
            for (java.util.UUID m : f.members()) names.append(manager.byId(m).map(BotIdentity::name).orElse("?")).append(m.equals(f.founder()) ? "*" : "").append(' ');
            Msg.raw(s, "  §6" + f.name() + " §7(" + f.members().size() + "): " + names.toString().trim() + "  §8(* = founder)");
        }
    }

    private void memory(CommandSender s, BotIdentity b) {
        SocialState.BotSocial bs = social.state().of(b.id());
        Msg.info(s, "§e" + b.name() + "§7 feelings (" + bs.relationships.size() + " known):");
        bs.relationships.entrySet().stream().sorted((x, y) -> Double.compare(y.getValue().familiarity(), x.getValue().familiarity())).limit(6).forEach(en -> {
            String who = org.bukkit.Bukkit.getPlayer(en.getKey()) != null ? org.bukkit.Bukkit.getPlayer(en.getKey()).getName()
                : manager.byId(en.getKey()).map(BotIdentity::name).orElse("(offline player)");
            var r = en.getValue();
            Msg.raw(s, String.format("  §7%s: %s (affinity %.0f, trust %.0f)", who, r.stance(), r.affinity(), r.trust()));
        });
        Msg.info(s, "Memories (" + bs.memory.size() + "):");
        bs.memory.all().stream().sorted((x, y) -> Long.compare(y.at(), x.at())).limit(6).forEach(m -> Msg.raw(s, "  §7[" + m.importance() + "] " + m.text()));
        Msg.info(s, "Rumors (" + bs.rumors.size() + "):");
        bs.rumors.all().forEach(r -> Msg.raw(s, "  §7" + r.subject() + " - " + r.where() + " (sure " + r.confidence() + "%, passed " + r.hops() + "x)"));
    }

    private void inv(CommandSender s, BotIdentity b) {
        List<String> lines = b.inventory().describe();
        if (lines.isEmpty()) { Msg.info(s, b.name() + "'s inventory is empty."); return; }
        Msg.info(s, b.name() + "'s inventory (" + (36 - b.inventory().freeSlots()) + "/36 slots used):");
        lines.forEach(l -> Msg.raw(s, "  §7" + l));
    }

    private void enable(CommandSender s, BotIdentity b) {
        if (b.state() != LifecycleState.DISABLED) { Msg.err(s, b.name() + " is not disabled."); return; }
        b.setState(LifecycleState.INACTIVE);
        manager.saveAll(true);
        Msg.ok(s, b.name() + " enabled (INACTIVE; it will be used by the director or /mtreebots spawn).");
    }

    private void population(CommandSender s) {
        Settings st = settings.get();
        var plan = director.lastPlan();
        Msg.info(s, "Bots: " + manager.count() + "/" + st.population().maxPopulation() + " total | " + manager.activeCount() + "/" + st.lifecycle().maxActiveBots() + " active");
        Msg.info(s, "Eligible players: " + director.eligiblePlayers().size() + " | director " + (st.population().enabled() ? "enabled" : "DISABLED") + ", every " + st.population().spawnIntervalSeconds() + "s");
        Msg.info(s, "Last plan: " + (plan == null ? "none yet" : plan.action() + " (" + plan.reason() + ", target " + plan.target() + ")"));
        Msg.info(s, "Last result: " + director.lastResult());
        Map<LifecycleState, Integer> by = new java.util.EnumMap<>(LifecycleState.class);
        manager.all().forEach(b -> by.merge(b.state(), 1, Integer::sum));
        Msg.info(s, "By state: " + by + " | encounter records: " + director.trackedEncounters());
    }

    private void save(CommandSender s) {
        manager.saveAll(false).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err == null) Msg.ok(s, "All bots saved.");
            else Msg.err(s, "Save FAILED: " + err.getMessage() + " (see console)");
        }));
    }

    // ---------------------------------------------------------------- tab completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (Sub sub : SUBS) if (can(s, sub) && sub.name().startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(sub.name());
            return out;
        }
        Sub sub = SUBS.stream().filter(x -> x.name().equals(a[0].toLowerCase(Locale.ROOT))).findFirst().orElse(null);
        if (sub == null || !can(s, sub)) return out;
        String cur = a[a.length - 1].toLowerCase(Locale.ROOT);
        boolean botArg = List.of("despawn", "remove", "info", "tp", "home", "give", "inv", "enable", "disable", "debug", "spawn", "goto", "follow", "stop", "resume", "equip", "build", "memory").contains(sub.name());
        if (a.length == 2 && botArg) {
            for (BotIdentity b : manager.all()) if (b.name().toLowerCase(Locale.ROOT).startsWith(cur)) out.add(b.name());
            if (sub.name().equals("debug") && "toggle".startsWith(cur)) out.add("toggle");
        } else if (a.length == 3 && sub.name().equals("home")) {
            for (String m : List.of("set", "clear", "show")) if (m.startsWith(cur)) out.add(m);
        } else if (a.length == 2 && sub.name().equals("faction")) {
            if ("list".startsWith(cur)) out.add("list");
        } else if (a.length == 3 && sub.name().equals("build")) {
            for (String m : blueprints.names()) if (m.startsWith(cur)) out.add(m);
            for (String m : List.of("status", "cancel")) if (m.startsWith(cur)) out.add(m);
        } else if (a.length == 3 && sub.name().equals("remove") && "confirm".startsWith(cur)) {
            out.add("confirm");
        } else if (a.length == 2 && sub.name().equals("spawnrandom")) {
            for (String n : List.of("1", "2", "3", "5", "10")) if (n.startsWith(cur)) out.add(n);
        }
        return out;
    }
}
