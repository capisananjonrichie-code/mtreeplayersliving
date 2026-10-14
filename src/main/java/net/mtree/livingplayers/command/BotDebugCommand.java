package net.mtree.livingplayers.command;

import net.mtree.livingplayers.bot.BotIdentity;
import net.mtree.livingplayers.bot.BotLocation;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.bot.Trait;
import net.mtree.livingplayers.combat.CombatAI;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.IntegrationManager;
import net.mtree.livingplayers.core.Msg;
import net.mtree.livingplayers.entity.BotEntityAdapter;
import net.mtree.livingplayers.building.BuildingAI;
import net.mtree.livingplayers.nav.BotNavigator;
import net.mtree.livingplayers.survival.SurvivalAI;
import net.mtree.livingplayers.storage.BotStorage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.LivingEntity;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** /mtreebots debug [name|toggle] - diagnostics for stuck bots and failed spawns. */
public final class BotDebugCommand {
    private final BotManager manager;
    private final BotEntityAdapter adapter;
    private final BotStorage storage;
    private final IntegrationManager integrations;
    private final Supplier<String> version;
    private final BotNavigator navigator;
    private final CombatAI combat;
    private final SurvivalAI survival;
    private final BuildingAI building;

    public BotDebugCommand(BotManager manager, BotEntityAdapter adapter, BotStorage storage, IntegrationManager integrations, BotNavigator navigator, CombatAI combat, SurvivalAI survival, BuildingAI building, Supplier<String> version) {
        this.manager = manager; this.adapter = adapter; this.storage = storage; this.integrations = integrations; this.navigator = navigator; this.combat = combat; this.survival = survival; this.building = building; this.version = version;
    }

    public void handle(CommandSender s, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("toggle")) {
            Msg.ok(s, "Debug logging is now " + (Debug.toggle() ? "ON" : "OFF") + " (until restart/reload).");
            return;
        }
        if (args.length >= 2) { bot(s, args[1]); return; }
        global(s);
    }

    private void global(CommandSender s) {
        Msg.info(s, "Plugin " + version.get() + " | Minecraft " + Bukkit.getMinecraftVersion() + " | representation: " + adapter.representation());
        Msg.info(s, "Ready: " + manager.isReady() + " | schema v" + storage.schemaVersion() + " | bots " + manager.count()
            + " (active " + manager.activeCount() + ", tracked entities " + adapter.trackedBotIds().size() + ")");
        double[] tps = Bukkit.getTPS();
        Msg.info(s, String.format("TPS (1m) %.2f | debug logging %s", tps[0], Debug.enabled() ? "ON" : "OFF"));
        Msg.info(s, "Optional plugins (presence only - detected does NOT mean tested):");
        for (IntegrationManager.Status st : integrations.report())
            Msg.raw(s, "  " + (st.present() ? "§a+ " : "§8- ") + "§f" + st.plugin() + " §8" + st.note());
        Msg.info(s, "Claim plugins present: " + (integrations.claimPluginsPresent().isEmpty() ? "none" : integrations.claimPluginsPresent()));
    }

    private void bot(CommandSender s, String name) {
        Optional<BotIdentity> ob = manager.find(name);
        if (ob.isEmpty()) { Msg.err(s, "No bot '" + name + "'."); return; }
        BotIdentity b = ob.get();
        Msg.info(s, "§e" + b.name() + " §7id=" + b.id() + " state=§f" + b.state() + "§7 dirty=" + b.dirty());
        Optional<LivingEntity> e = adapter.entity(b.id());
        Msg.info(s, "entity: " + (e.isPresent() ? "valid uuid=" + e.get().getUniqueId() + " hp=" + String.format("%.1f", e.get().getHealth()) : "none")
            + " | tracked=" + adapter.trackedBotIds().contains(b.id()));
        BotLocation l = b.lastLocation();
        if (l == null) Msg.info(s, "last location: none");
        else {
            Location loc = manager.toLocation(l);
            boolean worldOk = loc != null;
            boolean chunk = worldOk && loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4);
            Msg.info(s, String.format("last location: %s %.1f %.1f %.1f | world resolved=%s | chunk loaded=%s", l.worldName(), l.x(), l.y(), l.z(), worldOk, chunk));
        }
        Msg.info(s, "last saved " + (System.currentTimeMillis() - b.lastSavedAt()) / 1000 + "s ago | deaths=" + b.deathCount() + " | extra=" + b.extra());
        Msg.info(s, navigator.describe(b.id()));
        Msg.info(s, combat.describe(b.id()));
        Msg.info(s, survival.describe(b.id()));
        Msg.info(s, building.describe(b.id()));
        Msg.info(s, String.format("hunger %d | saturation %.1f", b.hunger(), b.saturation()));
        Msg.info(s, "skill=" + b.skill() + " style=" + b.style() + " goals=" + b.goals() + " skin=" + b.skinSpec());
        StringBuilder top = new StringBuilder();
        b.personality().asMap().entrySet().stream().sorted(Map.Entry.<Trait, Double>comparingByValue(Comparator.reverseOrder())).limit(4)
            .forEach(en -> top.append(en.getKey().key()).append('=').append(String.format("%.2f ", en.getValue())));
        Msg.info(s, "top traits: " + top);
        Msg.info(s, "inventory free slots: " + b.inventory().freeSlots() + "/36");
    }
}
