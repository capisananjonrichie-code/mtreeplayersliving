package net.mtree.livingplayers.storage;

import net.mtree.livingplayers.bot.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.CompletionException;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BotStorageTest {
    private static final Logger LOG = Logger.getLogger("test");

    static BotRecord rec(String name, LifecycleState st) {
        Random r = new Random(name.hashCode());
        Personality p = PersonalityGenerator.generate(r, null);
        Map<String, String> inv = new LinkedHashMap<>();
        inv.put("s0", "QUJD"); inv.put("a3", "REVG");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("respawn_at", "12345");
        return new BotRecord(UUID.randomUUID(), name, "player:Foo", st,
            new BotLocation(UUID.randomUUID(), "world", 10.5, 64, -20.25, 90f, 10f),
            17.5, 15, 3.5f, 1234L, p, CombatSkill.ADVANCED, CombatStyle.SHIELD_SPECIALIST,
            List.of("COLLECT_DIAMONDS", "CREATE_FARM"),
            new BotLocation(null, "world", 1, 2, 3, 0, 0), "faction-a",
            inv, extra, 2, 1000L, 2000L, BotRecord.CURRENT_RECORD_VERSION);
    }

    static BotStorage open(Path dir, List<Migration> migrations, int backups) {
        BotStorage s = new BotStorage(dir.resolve("bots.db"), LOG, migrations, backups);
        s.open().join();
        return s;
    }

    @Test void roundTripPreservesEveryField(@TempDir Path dir) {
        BotRecord a = rec("Tala", LifecycleState.ACTIVE);
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            s.save(a).join();
            List<BotRecord> loaded = s.loadAll().join();
            assertEquals(1, loaded.size());
            BotRecord b = loaded.get(0);
            assertEquals(a.id(), b.id());
            assertEquals(a.name(), b.name());
            assertEquals(a.skinSpec(), b.skinSpec());
            assertEquals(a.state(), b.state());
            assertEquals(a.lastLocation(), b.lastLocation());
            assertEquals(a.home(), b.home());
            assertEquals(a.health(), b.health());
            assertEquals(a.hunger(), b.hunger());
            assertEquals(a.saturation(), b.saturation());
            assertEquals(a.experience(), b.experience());
            assertEquals(a.skill(), b.skill());
            assertEquals(a.style(), b.style());
            assertEquals(a.goals(), b.goals());
            assertEquals(a.factionId(), b.factionId());
            assertEquals(a.inventory(), b.inventory());
            assertEquals(a.extra(), b.extra());
            assertEquals(a.deathCount(), b.deathCount());
            assertEquals(a.createdAt(), b.createdAt());
            for (Trait t : Trait.values()) assertEquals(a.personality().get(t), b.personality().get(t), 1e-12);
        }
    }

    @Test void nullLocationsAndFactionSurvive(@TempDir Path dir) {
        BotRecord a = rec("Mara", LifecycleState.INACTIVE);
        BotRecord n = new BotRecord(a.id(), a.name(), a.skinSpec(), a.state(), null, a.health(), a.hunger(), a.saturation(),
            a.experience(), a.personality(), a.skill(), a.style(), a.goals(), null, null, Map.of(), Map.of(), 0, 1, 2, 1);
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            s.save(n).join();
            BotRecord b = s.loadAll().join().get(0);
            assertNull(b.lastLocation()); assertNull(b.home()); assertNull(b.factionId());
            assertTrue(b.inventory().isEmpty());
        }
    }

    @Test void upsertDoesNotDuplicateAndRestartKeepsOneRowPerBot(@TempDir Path dir) {
        BotRecord a = rec("Kuya_Jun", LifecycleState.ACTIVE);
        for (int restart = 0; restart < 5; restart++) {
            try (BotStorage s = open(dir, Migrations.ALL, 0)) {
                s.save(a).join();
                s.save(a).join();
                assertEquals(1, s.loadAll().join().size(), "after restart " + restart);
            }
        }
    }

    @Test void updatesAreApplied(@TempDir Path dir) {
        BotRecord a = rec("Ate_Mara", LifecycleState.ACTIVE);
        BotRecord moved = new BotRecord(a.id(), a.name(), a.skinSpec(), LifecycleState.INACTIVE,
            new BotLocation(null, "world_nether", 1, 2, 3, 0, 0), 5, 4, 1f, 99, a.personality(), a.skill(), a.style(),
            List.of("FORM_GROUP"), null, null, Map.of("s1", "WFla"), Map.of(), 3, a.createdAt(), 5000, 1);
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            s.save(a).join();
            s.save(moved).join();
            BotRecord b = s.loadAll().join().get(0);
            assertEquals(LifecycleState.INACTIVE, b.state());
            assertEquals("world_nether", b.lastLocation().worldName());
            assertNull(b.home());
            assertEquals(List.of("FORM_GROUP"), b.goals());
            assertEquals(3, b.deathCount());
        }
    }

    @Test void failedBatchRollsBackEverything(@TempDir Path dir) {
        BotRecord ok = rec("Alpha", LifecycleState.INACTIVE);
        BotRecord clash = rec("Alpha", LifecycleState.INACTIVE); // different id, same name -> UNIQUE violation
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            CompletionException ex = assertThrows(CompletionException.class, () -> s.saveAll(List.of(ok, clash)).join());
            assertInstanceOf(StorageException.class, ex.getCause());
            assertTrue(s.loadAll().join().isEmpty(), "partial batch must not be committed");
            s.save(ok).join(); // storage remains usable after a failed batch
            assertEquals(1, s.loadAll().join().size());
        }
    }

    @Test void deleteRemovesOnlyThatBot(@TempDir Path dir) {
        BotRecord a = rec("A_bot", LifecycleState.INACTIVE), b = rec("B_bot", LifecycleState.INACTIVE);
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            s.saveAll(List.of(a, b)).join();
            assertTrue(s.delete(a.id()).join());
            assertFalse(s.delete(a.id()).join());
            List<BotRecord> left = s.loadAll().join();
            assertEquals(1, left.size());
            assertEquals(b.id(), left.get(0).id());
        }
    }

    @Test void migrationsUpgradeExistingDatabaseAndKeepData(@TempDir Path dir) throws Exception {
        BotRecord a = rec("Tala", LifecycleState.INACTIVE);
        List<Migration> v1Only = Migrations.ALL.subList(0, 1);
        try (BotStorage s = open(dir, v1Only, 0)) { s.save(a).join(); assertEquals(1, s.schemaVersion()); }

        try (BotStorage s = open(dir, Migrations.ALL, 0)) {          // plugin upgrade: V1 -> V2
            assertEquals(Migrations.latest(), s.schemaVersion());
            List<BotRecord> loaded = s.loadAll().join();
            assertEquals(1, loaded.size());
            assertEquals(a.id(), loaded.get(0).id());
            assertTrue(s.loadPlacedBlocks().join().isEmpty());
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains("pre-migration-v1")), "pre-migration backup missing");
        }
    }

    @Test void failingMigrationRollsBackAndLeavesVersionUnchanged(@TempDir Path dir) {
        try (BotStorage s = open(dir, Migrations.ALL, 0)) { assertEquals(Migrations.latest(), s.schemaVersion()); }
        List<Migration> bad = new ArrayList<>(Migrations.ALL);
        bad.add(Migration.ofStatements(Migrations.latest() + 1, "broken", "ALTER TABLE bots ADD COLUMN good_col TEXT", "THIS IS NOT SQL"));
        BotStorage s2 = new BotStorage(dir.resolve("bots.db"), LOG, bad, 0);
        assertThrows(CompletionException.class, () -> s2.open().join());
        s2.close();
        try (BotStorage s3 = open(dir, Migrations.ALL, 0)) { assertEquals(Migrations.latest(), s3.schemaVersion()); }
    }

    @Test void refusesDatabaseNewerThanPlugin(@TempDir Path dir) {
        List<Migration> future = new ArrayList<>(Migrations.ALL);
        future.add(Migration.ofStatements(Migrations.latest() + 1, "future", "ALTER TABLE bots ADD COLUMN future TEXT"));
        try (BotStorage s = open(dir, future, 0)) { assertEquals(Migrations.latest() + 1, s.schemaVersion()); }
        BotStorage old = new BotStorage(dir.resolve("bots.db"), LOG, Migrations.ALL, 0);
        CompletionException ex = assertThrows(CompletionException.class, () -> old.open().join());
        assertTrue(ex.getCause().getMessage().contains("newer"));
        old.close();
    }

    @Test void placedBlocksPersistAcrossRestartsAndCanBeRemoved(@TempDir Path dir) {
        UUID world = UUID.randomUUID(), bot = UUID.randomUUID();
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            s.addPlacedBlock(new PlacedBlock(world, 1, 64, 2, bot, "CRAFTING_TABLE", 111L)).join();
            s.addPlacedBlock(new PlacedBlock(world, 1, 64, 2, bot, "CRAFTING_TABLE", 222L)).join(); // same position: replaced, not duplicated
            s.addPlacedBlock(new PlacedBlock(world, 5, 70, 5, bot, "CRAFTING_TABLE", 333L)).join();
        }
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            List<PlacedBlock> all = s.loadPlacedBlocks().join();
            assertEquals(2, all.size());
            assertTrue(all.stream().anyMatch(b -> b.x() == 1 && b.placedAt() == 222L));
            s.removePlacedBlock(world, 1, 64, 2).join();
            assertEquals(1, s.loadPlacedBlocks().join().size());
        }
    }

    @Test void constructionProjectsPersistUpdateAndResume(@TempDir Path dir) {
        UUID id = UUID.randomUUID(), bot = UUID.randomUUID(), world = UUID.randomUUID();
        ProjectRecord p = new ProjectRecord(id, bot, "starter_hut", world, 10, 64, -5, 2, Map.of("PLANKS", "OAK_PLANKS"), "f0", "BUILDING", 1, 100L, 100L);
        try (BotStorage s = open(dir, Migrations.ALL, 0)) { s.saveProject(p).join(); }
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {                       // "restart"
            List<ProjectRecord> l = s.loadProjects().join();
            assertEquals(1, l.size());
            assertEquals(p, l.get(0));
            assertTrue(l.get(0).active());
            ProjectRecord more = new ProjectRecord(id, bot, "starter_hut", world, 10, 64, -5, 2, Map.of("PLANKS", "OAK_PLANKS"), "ffff0", "DONE", 1, 100L, 200L);
            s.saveProject(more).join();
            List<ProjectRecord> l2 = s.loadProjects().join();
            assertEquals(1, l2.size(), "update, not duplicate");
            assertEquals("ffff0", l2.get(0).progressHex());
            assertFalse(l2.get(0).active());
        }
    }

    @Test void socialDataSurvivesRestartAndIsReplacedAtomically(@TempDir Path dir) {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), player = UUID.randomUUID();
        net.mtree.livingplayers.bot.Personality p = net.mtree.livingplayers.bot.PersonalityGenerator.generate(new Random(1), null);
        net.mtree.livingplayers.social.SocialState st = new net.mtree.livingplayers.social.SocialState(20, 10, 4);
        st.record(a, player, true, net.mtree.livingplayers.social.SocialEvent.GIFT, p, 5000);
        st.record(a, b, false, net.mtree.livingplayers.social.SocialEvent.HELPED, p, 6000);
        st.remember(a, new net.mtree.livingplayers.social.MemoryStore.Memory("GIFTED_BY", player, "gave me wood", 6, 5000));
        st.remember(a, new net.mtree.livingplayers.social.MemoryStore.Memory("NOTE", null, "no subject", 2, 5100));
        st.hear(b, new net.mtree.livingplayers.social.Rumor(UUID.randomUUID(), "THREAT", "creeper nest", "not far to the east", 66, 1, 7000));
        st.factions.create("Samahan ng Tala", a, 8000);
        st.factions.join(st.factions.of(a).orElseThrow().id(), b);
        try (BotStorage s = open(dir, Migrations.ALL, 0)) { s.saveSocial(st.snapshot()).join(); }
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            net.mtree.livingplayers.social.SocialState back = new net.mtree.livingplayers.social.SocialState(20, 10, 4);
            back.load(s.loadSocial().join(), Set.of(a, b));
            assertEquals(st.relationship(a, player), back.relationship(a, player));
            assertTrue(back.of(a).otherIsPlayer.get(player));
            assertFalse(back.of(a).otherIsPlayer.get(b));
            assertEquals(2, back.of(a).memory.size());
            assertEquals(st.of(b).rumors.all().get(0).where(), back.of(b).rumors.all().get(0).where());
            assertEquals("Samahan ng Tala", back.factions.of(b).orElseThrow().name());
            assertTrue(back.factions.sameFaction(a, b));
            // a bot deleted while offline is dropped on load; the faction survives with the remaining member
            net.mtree.livingplayers.social.SocialState partial = new net.mtree.livingplayers.social.SocialState(20, 10, 4);
            partial.load(s.loadSocial().join(), Set.of(b));
            assertEquals(Relationship_NONE(), partial.relationship(a, player));
            assertEquals(b, partial.factions.of(b).orElseThrow().founder());
            // replace-all: saving an empty snapshot leaves nothing behind
            s.saveSocial(net.mtree.livingplayers.storage.SocialData.empty()).join();
            assertTrue(s.loadSocial().join().relationships().isEmpty());
            assertTrue(s.loadSocial().join().factions().isEmpty());
        }
    }
    private static net.mtree.livingplayers.social.Relationship Relationship_NONE() { return net.mtree.livingplayers.social.Relationship.NONE; }

    @Test void corruptDatabaseIsQuarantinedNotOverwritten(@TempDir Path dir) throws Exception {
        Path db = dir.resolve("bots.db");
        Files.write(db, "this is definitely not an sqlite file, just garbage bytes 0123456789".repeat(40).getBytes());
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            assertTrue(s.wasQuarantinedOnOpen());
            assertTrue(s.loadAll().join().isEmpty());
            s.save(rec("Fresh", LifecycleState.INACTIVE)).join();
        }
        try (Stream<Path> files = Files.list(dir)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().contains(".corrupt-")), "corrupt file must be preserved");
        }
    }

    @Test void unreadableRowIsSkippedButOthersLoad(@TempDir Path dir) throws Exception {
        BotRecord good = rec("Good_Bot", LifecycleState.INACTIVE);
        try (BotStorage s = open(dir, Migrations.ALL, 0)) { s.save(good).join(); }
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("bots.db")); Statement st = c.createStatement()) {
            st.execute("INSERT INTO bots(id,name,state,health,hunger,personality_json,combat_skill,combat_style,created_at,last_saved_at) "
                + "VALUES('not-a-uuid','Broken','INACTIVE',20,20,'{}','BEGINNER','TEAM_FIGHTER',1,1)");
        }
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            List<BotRecord> l = s.loadAll().join();
            assertEquals(1, l.size());
            assertEquals(good.id(), l.get(0).id());
        }
    }

    @Test void backupsArePrunedToConfiguredCount(@TempDir Path dir) throws Exception {
        try (BotStorage s = open(dir, Migrations.ALL, 2)) {
            s.save(rec("Backup_Me", LifecycleState.INACTIVE)).join();
            for (int i = 0; i < 4; i++) { assertNotNull(s.backupNow().join()); Thread.sleep(5); }
        }
        try (Stream<Path> files = Files.list(dir.resolve("backups"))) {
            assertEquals(2, files.filter(p -> p.getFileName().toString().endsWith(".db")).count());
        }
    }

    @Test void nameUniquenessIsCaseInsensitive(@TempDir Path dir) {
        try (BotStorage s = open(dir, Migrations.ALL, 0)) {
            s.save(rec("Tala", LifecycleState.INACTIVE)).join();
            assertThrows(CompletionException.class, () -> s.save(rec("TALA", LifecycleState.INACTIVE)).join());
        }
    }
}
