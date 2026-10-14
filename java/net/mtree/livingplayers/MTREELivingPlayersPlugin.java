package net.mtree.livingplayers;

import net.mtree.livingplayers.bot.BotLifecycleManager;
import net.mtree.livingplayers.bot.BotManager;
import net.mtree.livingplayers.building.BlueprintManager;
import net.mtree.livingplayers.building.BuildingAI;
import net.mtree.livingplayers.chat.BotChat;
import net.mtree.livingplayers.chat.ConversationService;
import net.mtree.livingplayers.chat.DialogueEngine;
import net.mtree.livingplayers.listener.ChatListener;
import net.mtree.livingplayers.social.SocialService;
import net.mtree.livingplayers.social.SocialState;
import net.mtree.livingplayers.storage.SocialData;
import net.mtree.livingplayers.combat.BotEquipmentService;
import net.mtree.livingplayers.combat.BotVitalsService;
import net.mtree.livingplayers.combat.CombatAI;
import net.mtree.livingplayers.command.BotCommand;
import net.mtree.livingplayers.command.BotDebugCommand;
import net.mtree.livingplayers.core.ConfigService;
import net.mtree.livingplayers.core.Debug;
import net.mtree.livingplayers.core.IntegrationManager;
import net.mtree.livingplayers.core.Settings;
import net.mtree.livingplayers.director.BotPopulationDirector;
import net.mtree.livingplayers.entity.MannequinAdapter;
import net.mtree.livingplayers.entity.SkinService;
import net.mtree.livingplayers.listener.BotEntityListener;
import net.mtree.livingplayers.nav.BotNavigator;
import net.mtree.livingplayers.protect.ProtectionService;
import net.mtree.livingplayers.storage.BotStorage;
import net.mtree.livingplayers.storage.PlacedBlock;
import net.mtree.livingplayers.storage.ProjectRecord;
import net.mtree.livingplayers.survival.BlockInteractionService;
import net.mtree.livingplayers.survival.BotPlacedBlocks;
import net.mtree.livingplayers.survival.SurvivalAI;
import net.mtree.livingplayers.storage.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.logging.Level;

/**
 * MTREE: Living Players - Stage 1 (foundation). Startup order: config -> storage (async open+load) ->
 * main-thread finish (register bots, sweep orphan entities, start timers). Nothing spawns until the database
 * has loaded successfully; if it cannot, the plugin disables itself rather than run without persistence.
 */
public final class MTREELivingPlayersPlugin extends JavaPlugin {
    private ConfigService config;
    private BotStorage storage;
    private BotManager manager;
    private MannequinAdapter adapter;
    private BotLifecycleManager lifecycle;
    private BotPopulationDirector director;
    private BotNavigator navigator;
    private BotVitalsService vitals;
    private CombatAI combat;
    private SurvivalAI survival;
    private BuildingAI building;
    private SocialService socialService;
    private SocialState socialState;
    private ConversationService conversations;
    private BotChat botChat;
    private BlueprintManager blueprints;
    private BotPlacedBlocks placedBlocks;

    @Override
    public void onEnable() {
        if (!serverSupported()) { getServer().getPluginManager().disablePlugin(this); return; }

        config = new ConfigService(this);
        config.load();
        Debug.init(getLogger(), config.settings().debug());

        Executor sync = r -> { if (isEnabled()) getServer().getScheduler().runTask(this, r); };
        IntegrationManager integrations = new IntegrationManager(config::settings);
        storage = new BotStorage(getDataFolder().toPath().resolve("bots.db"), getLogger(), Migrations.ALL, config.settings().persistence().backupsToKeep());
        adapter = new MannequinAdapter(this, new SkinService(getLogger(), sync), config::settings);
        manager = new BotManager(this, config::settings, storage, adapter);
        placedBlocks = new BotPlacedBlocks(storage, getLogger());
        ProtectionService protection = new ProtectionService(config::settings, integrations, placedBlocks);
        director = new BotPopulationDirector(this, config::settings, manager, adapter, protection);
        navigator = new BotNavigator(this, config::settings, manager, adapter);
        BotEquipmentService equipment = new BotEquipmentService(adapter);
        vitals = new BotVitalsService(this, config::settings, manager, adapter);
        combat = new CombatAI(this, config::settings, manager, adapter, navigator, equipment, vitals);
        BlockInteractionService blockService = new BlockInteractionService(config::settings, adapter, protection, placedBlocks, vitals);
        survival = new SurvivalAI(this, config::settings, manager, adapter, navigator, combat, blockService, protection, equipment);
        blueprints = new BlueprintManager(this);
        blueprints.load();
        building = new BuildingAI(this, config::settings, manager, adapter, navigator, combat, survival, blockService, blueprints, storage);
        Settings sSet = config.settings();
        socialState = new SocialState(sSet.social().memoryMax(), sSet.social().rumorMax(), sSet.social().maxFactionSize());
        botChat = new BotChat(this, config::settings, adapter);
        socialService = new SocialService(this, config::settings, manager, adapter, combat, botChat, storage, socialState);
        conversations = new ConversationService(this, config::settings, manager, adapter, navigator, combat, survival, building, botChat, socialState);
        manager.addRemovalListener(socialService::onRemoved);
        survival.setOnLadderDone(b -> socialService.onMilestone(b, DialogueEngine.AmbientTopic.GOT_TOOLS));
        building.setOnFinished(b -> socialService.onMilestone(b, DialogueEngine.AmbientTopic.FINISHED_BUILD));
        lifecycle = new BotLifecycleManager(this, config::settings, manager, adapter, director::findSpotNearAnyPlayer);

        getServer().getPluginManager().registerEvents(new BotEntityListener(manager, adapter, config::settings, combat, socialService), this);

        getServer().getPluginManager().registerEvents(new ChatListener(this, conversations), this);

        PluginCommand cmd = getCommand("mtreebots");
        if (cmd == null) { getLogger().severe("Command 'mtreebots' missing from plugin.yml"); getServer().getPluginManager().disablePlugin(this); return; }
        BotDebugCommand dbg = new BotDebugCommand(manager, adapter, storage, integrations, navigator, combat, survival, building, () -> getPluginMeta().getVersion());
        BotCommand bc = new BotCommand(this, config::settings, manager, adapter, director, dbg, navigator, equipment, building, blueprints, socialService, this::reloadAll);
        cmd.setExecutor(bc);
        cmd.setTabCompleter(bc);

        storage.open().thenCompose(v -> storage.loadAll().thenCombine(storage.loadPlacedBlocks(), Loaded::new).thenCombine(storage.loadProjects(), Loaded::withProjects).thenCombine(storage.loadSocial(), Loaded::withSocial))
            .whenComplete((loaded, err) -> sync.execute(() -> finishStartup(loaded, err, integrations)));
    }

    private record Loaded(List<net.mtree.livingplayers.bot.BotRecord> bots, List<PlacedBlock> placed, List<ProjectRecord> projects, SocialData social) {
        Loaded(List<net.mtree.livingplayers.bot.BotRecord> bots, List<PlacedBlock> placed) { this(bots, placed, List.of(), SocialData.empty()); }
        Loaded withProjects(List<ProjectRecord> p) { return new Loaded(bots, placed, p, social); }
        Loaded withSocial(SocialData sd) { return new Loaded(bots, placed, projects, sd); }
    }

    private void finishStartup(Loaded loaded, Throwable err, IntegrationManager integrations) {
        if (err != null) {
            getLogger().log(Level.SEVERE, "Bot database could not be opened/loaded. Disabling the plugin instead of running without persistence.", err);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (storage.wasQuarantinedOnOpen()) getLogger().severe("A corrupt database was quarantined at startup - check plugins/MTREELivingPlayers/ and backups/.");
        manager.load(loaded.bots());
        placedBlocks.load(loaded.placed());
        building.load(loaded.projects());
        socialState.load(loaded.social(), manager.all().stream().map(net.mtree.livingplayers.bot.BotIdentity::id).collect(java.util.stream.Collectors.toSet()));
        int swept = adapter.sweepOrphans(Bukkit.getWorlds());
        if (swept > 0) getLogger().info("Removed " + swept + " orphaned bot entit" + (swept == 1 ? "y" : "ies") + " left from a previous session.");
        lifecycle.start();
        director.start();
        navigator.start();
        vitals.start();
        combat.start();
        survival.start();
        building.start();
        socialService.start();
        if (config.settings().integrations().logDetected())
            integrations.report().stream().filter(s -> s.present()).forEach(s -> getLogger().info("Optional plugin detected: " + s.plugin() + " - " + s.note()));
        getLogger().info("MTREE Living Players ready (representation: " + adapter.representation() + ", schema v" + storage.schemaVersion() + ").");
    }

    private boolean serverSupported() {
        String mc = Bukkit.getMinecraftVersion();
        if (!"1.21.11".equals(mc)) getLogger().warning("Built for Paper 1.21.11 but running " + mc + ". Mannequin API behaviour may differ.");
        try {
            Class.forName("org.bukkit.entity.Mannequin");
            Class.forName("io.papermc.paper.datacomponent.item.ResolvableProfile");
            return true;
        } catch (ClassNotFoundException e) {
            getLogger().severe("This server has no Mannequin entity API (needs Paper 1.21.9+). Disabling.");
            return false;
        }
    }

    private void reloadAll() {
        config.load();
        botChat.rebuildLimiter();
        conversations.configureModel();
        Debug.init(getLogger(), config.settings().debug());
        lifecycle.start();
        director.start();
    }

    @Override
    public void onDisable() {
        if (director != null) director.stop();
        if (socialService != null) { socialService.stop(); if (manager != null && manager.isReady()) socialService.saveNow(); }
        if (building != null) building.stop();
        if (survival != null) survival.stop();
        if (combat != null) combat.stop();
        if (vitals != null) vitals.stop();
        if (navigator != null) navigator.stop();
        if (lifecycle != null) lifecycle.stop();
        if (manager != null) manager.shutdown();
        if (storage != null) storage.close();
    }
}
