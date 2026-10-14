package net.mtree.livingplayers.storage;

import net.mtree.livingplayers.bot.BotCodec;
import net.mtree.livingplayers.bot.BotLocation;
import net.mtree.livingplayers.bot.BotRecord;
import net.mtree.livingplayers.bot.CombatSkill;
import net.mtree.livingplayers.bot.CombatStyle;
import net.mtree.livingplayers.bot.LifecycleState;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * SQLite persistence for bot identities.
 *
 * <ul>
 *   <li>One dedicated IO thread owns the single JDBC connection; every public method hops onto it.</li>
 *   <li>Only immutable {@link BotRecord}s cross the thread boundary - no Bukkit objects, ever.</li>
 *   <li>WAL + synchronous=FULL; every write batch is one transaction (all-or-nothing).</li>
 *   <li>Schema is versioned; migrations run transactionally after a file backup.</li>
 *   <li>A corrupt database is quarantined (renamed), never silently overwritten.</li>
 * </ul>
 */
public final class BotStorage implements AutoCloseable {
    private final Path file;
    private final Logger log;
    private final List<Migration> migrations;
    private final int backupsToKeep;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MTREEBots-Storage");
        t.setDaemon(true);
        return t;
    });
    private Connection conn; // IO thread only
    private volatile int schemaVersion = -1;
    private volatile boolean quarantinedOnOpen = false;

    public BotStorage(Path file, Logger log, List<Migration> migrations, int backupsToKeep) {
        this.file = file;
        this.log = log;
        this.migrations = List.copyOf(migrations);
        this.backupsToKeep = Math.max(0, backupsToKeep);
    }

    public int schemaVersion() { return schemaVersion; }
    public boolean wasQuarantinedOnOpen() { return quarantinedOnOpen; }

    // ------------------------------------------------------------------ public async API

    public CompletableFuture<Void> open() { return CompletableFuture.runAsync(this::openBlocking, io); }

    public CompletableFuture<List<BotRecord>> loadAll() { return CompletableFuture.supplyAsync(this::loadAllBlocking, io); }

    public CompletableFuture<Void> saveAll(Collection<BotRecord> records) {
        List<BotRecord> copy = List.copyOf(records);
        return CompletableFuture.runAsync(() -> saveAllBlocking(copy), io);
    }

    public CompletableFuture<Void> save(BotRecord record) { return saveAll(List.of(record)); }

    public CompletableFuture<Boolean> delete(UUID id) { return CompletableFuture.supplyAsync(() -> deleteBlocking(id), io); }

    public CompletableFuture<List<PlacedBlock>> loadPlacedBlocks() { return CompletableFuture.supplyAsync(this::loadPlacedBlocksBlocking, io); }

    public CompletableFuture<Void> addPlacedBlock(PlacedBlock b) { return CompletableFuture.runAsync(() -> addPlacedBlocking(b), io); }

    public CompletableFuture<Void> removePlacedBlock(UUID world, int x, int y, int z) { return CompletableFuture.runAsync(() -> removePlacedBlocking(world, x, y, z), io); }

    public CompletableFuture<List<ProjectRecord>> loadProjects() { return CompletableFuture.supplyAsync(this::loadProjectsBlocking, io); }

    public CompletableFuture<Void> saveProject(ProjectRecord p) { return CompletableFuture.runAsync(() -> saveProjectBlocking(p), io); }

    public CompletableFuture<SocialData> loadSocial() { return CompletableFuture.supplyAsync(this::loadSocialBlocking, io); }

    /** Replaces ALL social data in one transaction (small data, so simple and atomic beats clever). */
    public CompletableFuture<Void> saveSocial(SocialData d) { return CompletableFuture.runAsync(() -> saveSocialBlocking(d), io); }

    public CompletableFuture<Path> backupNow() { return CompletableFuture.supplyAsync(this::backupBlocking, io); }

    /** Drains queued writes, closes the connection, stops the IO thread. Blocks up to 20s. */
    @Override
    public void close() {
        try {
            io.submit(() -> {
                try { if (conn != null && !conn.isClosed()) conn.close(); }
                catch (SQLException e) { log.log(Level.WARNING, "Error closing bot database", e); }
            }).get(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.log(Level.WARNING, "Bot storage did not close cleanly", e);
        } finally {
            io.shutdown();
        }
    }

    // ------------------------------------------------------------------ IO-thread implementation

    private void openBlocking() {
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            try { Class.forName("org.sqlite.JDBC"); }
            catch (ClassNotFoundException e) { throw new StorageException("SQLite JDBC driver not found (plugin.yml 'libraries' download failed?)", e); }

            conn = connectChecked();
            migrate();
            if (backupsToKeep > 0) backupBlocking();
        } catch (SQLException | IOException e) {
            throw new StorageException("Failed to open bot database " + file, e);
        }
    }

    private Connection connectChecked() throws SQLException, IOException {
        try {
            Connection c = connect();
            if (quickCheck(c)) return c;
            c.close();
            log.severe("Bot database failed its integrity check.");
        } catch (SQLException e) {
            if (!looksCorrupt(e)) throw e;
            log.log(Level.SEVERE, "Bot database appears corrupt", e);
        }
        quarantine();
        return connect();
    }

    private Connection connect() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=FULL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    private static boolean looksCorrupt(SQLException e) {
        String m = String.valueOf(e.getMessage()).toLowerCase();
        return m.contains("not a database") || m.contains("malformed") || m.contains("corrupt");
    }

    private boolean quickCheck(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        }
    }

    private void quarantine() throws IOException {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        for (String suffix : new String[] {"", "-wal", "-shm"}) {
            Path p = Path.of(file.toAbsolutePath() + suffix);
            if (Files.exists(p)) Files.move(p, Path.of(file.toAbsolutePath() + ".corrupt-" + stamp + suffix), StandardCopyOption.REPLACE_EXISTING);
        }
        quarantinedOnOpen = true;
        log.severe("Corrupt bot database moved aside as bots.db.corrupt-" + stamp + ". Starting with an EMPTY database; restore from backups/ if needed.");
    }

    private void migrate() throws SQLException, IOException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL, applied_at INTEGER NOT NULL, description TEXT)");
        }
        int current = 0;
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT COALESCE(MAX(version),0) FROM schema_version")) {
            if (rs.next()) current = rs.getInt(1);
        }
        int latest = migrations.isEmpty() ? 0 : migrations.get(migrations.size() - 1).version();
        if (current > latest) {
            throw new StorageException("Database schema v" + current + " is newer than this plugin supports (v" + latest + "). Refusing to open to avoid data loss.");
        }
        final int startVersion = current;
        boolean pending = migrations.stream().anyMatch(m -> m.version() > startVersion);
        if (pending && startVersion > 0) {
            Path pre = Path.of(file.toAbsolutePath() + ".pre-migration-v" + startVersion);
            try (Statement st = conn.createStatement()) { st.execute("VACUUM INTO '" + pre.toString().replace("'", "''") + "'"); }
            catch (SQLException e) { log.warning("Pre-migration backup failed: " + e.getMessage()); }
        }
        for (Migration m : migrations) {
            if (m.version() <= startVersion) continue;
            conn.setAutoCommit(false);
            try {
                m.step().apply(conn);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_version(version, applied_at, description) VALUES(?,?,?)")) {
                    ps.setInt(1, m.version());
                    ps.setLong(2, System.currentTimeMillis());
                    ps.setString(3, m.description());
                    ps.executeUpdate();
                }
                conn.commit();
                current = m.version();
                log.info("Bot database migrated to schema v" + m.version() + " (" + m.description() + ")");
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        }
        schemaVersion = current;
    }

    private Path backupBlocking() {
        try {
            Path dir = file.toAbsolutePath().getParent().resolve("backups");
            Files.createDirectories(dir);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS"));
            Path target = dir.resolve("bots-" + stamp + ".db");
            try (Statement st = conn.createStatement()) { st.execute("VACUUM INTO '" + target.toString().replace("'", "''") + "'"); }
            try (Stream<Path> s = Files.list(dir)) {
                List<Path> all = s.filter(p -> p.getFileName().toString().matches("bots-.*\\.db")).sorted().toList();
                for (int i = 0; i < all.size() - Math.max(1, backupsToKeep); i++) Files.deleteIfExists(all.get(i));
            }
            return target;
        } catch (SQLException | IOException e) {
            log.log(Level.WARNING, "Database backup failed", e);
            return null;
        }
    }

    private static final String UPSERT = """
        INSERT INTO bots (id,name,skin_spec,state,world_uuid,world_name,x,y,z,yaw,pitch,health,hunger,saturation,experience,
          personality_json,combat_skill,combat_style,goals_json,
          home_world_uuid,home_world_name,home_x,home_y,home_z,home_yaw,home_pitch,
          faction_id,inventory_json,extra_json,death_count,created_at,last_saved_at,record_version)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON CONFLICT(id) DO UPDATE SET
          name=excluded.name, skin_spec=excluded.skin_spec, state=excluded.state,
          world_uuid=excluded.world_uuid, world_name=excluded.world_name, x=excluded.x, y=excluded.y, z=excluded.z,
          yaw=excluded.yaw, pitch=excluded.pitch, health=excluded.health, hunger=excluded.hunger,
          saturation=excluded.saturation, experience=excluded.experience,
          personality_json=excluded.personality_json, combat_skill=excluded.combat_skill,
          combat_style=excluded.combat_style, goals_json=excluded.goals_json,
          home_world_uuid=excluded.home_world_uuid, home_world_name=excluded.home_world_name,
          home_x=excluded.home_x, home_y=excluded.home_y, home_z=excluded.home_z,
          home_yaw=excluded.home_yaw, home_pitch=excluded.home_pitch,
          faction_id=excluded.faction_id, inventory_json=excluded.inventory_json, extra_json=excluded.extra_json,
          death_count=excluded.death_count, last_saved_at=excluded.last_saved_at, record_version=excluded.record_version
        """;

    private void saveAllBlocking(List<BotRecord> records) {
        if (records.isEmpty()) return;
        try {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(UPSERT)) {
                for (BotRecord r : records) {
                    bind(ps, r);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
            } catch (SQLException | RuntimeException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to save " + records.size() + " bot(s)", e);
        }
    }

    private static void bind(PreparedStatement ps, BotRecord r) throws SQLException {
        int i = 1;
        ps.setString(i++, r.id().toString());
        ps.setString(i++, r.name());
        ps.setString(i++, r.skinSpec());
        ps.setString(i++, r.state().name());
        i = bindLoc(ps, i, r.lastLocation());
        ps.setDouble(i++, r.health());
        ps.setInt(i++, r.hunger());
        ps.setDouble(i++, r.saturation());
        ps.setLong(i++, r.experience());
        ps.setString(i++, BotCodec.personalityToJson(r.personality()));
        ps.setString(i++, r.skill().name());
        ps.setString(i++, r.style().name());
        ps.setString(i++, BotCodec.listToJson(r.goals()));
        i = bindLoc(ps, i, r.home());
        if (r.factionId() == null) ps.setNull(i++, Types.VARCHAR); else ps.setString(i++, r.factionId());
        ps.setString(i++, BotCodec.mapToJson(r.inventory()));
        ps.setString(i++, BotCodec.mapToJson(r.extra()));
        ps.setInt(i++, r.deathCount());
        ps.setLong(i++, r.createdAt());
        ps.setLong(i++, r.lastSavedAt());
        ps.setInt(i, r.recordVersion());
    }

    /** Binds uuid,name,x,y,z,yaw,pitch (7 columns) - all NULL when loc is null. */
    private static int bindLoc(PreparedStatement ps, int i, BotLocation l) throws SQLException {
        if (l == null) {
            ps.setNull(i++, Types.VARCHAR); ps.setNull(i++, Types.VARCHAR);
            for (int k = 0; k < 5; k++) ps.setNull(i++, Types.REAL);
            return i;
        }
        if (l.worldUuid() == null) ps.setNull(i++, Types.VARCHAR); else ps.setString(i++, l.worldUuid().toString());
        ps.setString(i++, l.worldName());
        ps.setDouble(i++, l.x()); ps.setDouble(i++, l.y()); ps.setDouble(i++, l.z());
        ps.setDouble(i++, l.yaw()); ps.setDouble(i++, l.pitch());
        return i;
    }

    private List<PlacedBlock> loadPlacedBlocksBlocking() {
        List<PlacedBlock> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM bot_placed_blocks")) {
            while (rs.next()) {
                try {
                    out.add(new PlacedBlock(UUID.fromString(rs.getString("world_uuid")), rs.getInt("x"), rs.getInt("y"), rs.getInt("z"),
                        UUID.fromString(rs.getString("bot_id")), rs.getString("material"), rs.getLong("placed_at")));
                } catch (IllegalArgumentException e) { log.warning("Skipping unreadable placed-block row"); }
            }
        } catch (SQLException e) { throw new StorageException("Failed to load placed blocks", e); }
        return out;
    }

    private void addPlacedBlocking(PlacedBlock b) {
        try (PreparedStatement ps = conn.prepareStatement("INSERT OR REPLACE INTO bot_placed_blocks(world_uuid,x,y,z,bot_id,material,placed_at) VALUES(?,?,?,?,?,?,?)")) {
            ps.setString(1, b.worldUuid().toString()); ps.setInt(2, b.x()); ps.setInt(3, b.y()); ps.setInt(4, b.z());
            ps.setString(5, b.botId().toString()); ps.setString(6, b.material()); ps.setLong(7, b.placedAt());
            ps.executeUpdate();
        } catch (SQLException e) { throw new StorageException("Failed to record placed block", e); }
    }

    private void removePlacedBlocking(UUID world, int x, int y, int z) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bot_placed_blocks WHERE world_uuid=? AND x=? AND y=? AND z=?")) {
            ps.setString(1, world.toString()); ps.setInt(2, x); ps.setInt(3, y); ps.setInt(4, z);
            ps.executeUpdate();
        } catch (SQLException e) { throw new StorageException("Failed to remove placed block", e); }
    }

    private List<ProjectRecord> loadProjectsBlocking() {
        List<ProjectRecord> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM construction_projects ORDER BY created_at")) {
            while (rs.next()) {
                try {
                    out.add(new ProjectRecord(UUID.fromString(rs.getString("id")), UUID.fromString(rs.getString("bot_id")), rs.getString("blueprint"),
                        UUID.fromString(rs.getString("world_uuid")), rs.getInt("ox"), rs.getInt("oy"), rs.getInt("oz"), rs.getInt("rotation"),
                        BotCodec.mapFromJson(rs.getString("materials_json")), rs.getString("progress_hex"), rs.getString("status"),
                        rs.getInt("skipped"), rs.getLong("created_at"), rs.getLong("updated_at")));
                } catch (RuntimeException e) { log.log(Level.SEVERE, "Skipping unreadable construction project row", e); }
            }
        } catch (SQLException e) { throw new StorageException("Failed to load construction projects", e); }
        return out;
    }

    private void saveProjectBlocking(ProjectRecord p) {
        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO construction_projects(id,bot_id,blueprint,world_uuid,ox,oy,oz,rotation,materials_json,progress_hex,status,skipped,created_at,updated_at) "
            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET materials_json=excluded.materials_json, progress_hex=excluded.progress_hex, "
            + "status=excluded.status, skipped=excluded.skipped, updated_at=excluded.updated_at")) {
            ps.setString(1, p.id().toString()); ps.setString(2, p.botId().toString()); ps.setString(3, p.blueprint());
            ps.setString(4, p.worldUuid().toString()); ps.setInt(5, p.ox()); ps.setInt(6, p.oy()); ps.setInt(7, p.oz()); ps.setInt(8, p.rotation());
            ps.setString(9, BotCodec.mapToJson(p.materials())); ps.setString(10, p.progressHex()); ps.setString(11, p.status());
            ps.setInt(12, p.skipped()); ps.setLong(13, p.createdAt()); ps.setLong(14, p.updatedAt());
            ps.executeUpdate();
        } catch (SQLException e) { throw new StorageException("Failed to save construction project " + p.id(), e); }
    }

    private SocialData loadSocialBlocking() {
        List<SocialData.Rel> rels = new ArrayList<>(); List<SocialData.Mem> mems = new ArrayList<>();
        List<SocialData.RumorRow> rumors = new ArrayList<>(); List<SocialData.FactionRow> factions = new ArrayList<>();
        try (Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT * FROM bot_relationships")) {
                while (rs.next()) try {
                    rels.add(new SocialData.Rel(UUID.fromString(rs.getString("bot_id")), UUID.fromString(rs.getString("other_id")), rs.getInt("other_is_player") != 0,
                        rs.getDouble("affinity"), rs.getDouble("trust"), rs.getDouble("familiarity"), rs.getLong("last_at")));
                } catch (IllegalArgumentException e) { log.warning("Skipping unreadable relationship row"); }
            }
            try (ResultSet rs = st.executeQuery("SELECT * FROM bot_memories ORDER BY id")) {
                while (rs.next()) try {
                    String subj = rs.getString("subject_id");
                    mems.add(new SocialData.Mem(UUID.fromString(rs.getString("bot_id")), rs.getString("kind"), subj == null ? null : UUID.fromString(subj),
                        rs.getString("text"), rs.getInt("importance"), rs.getLong("at")));
                } catch (IllegalArgumentException e) { log.warning("Skipping unreadable memory row"); }
            }
            try (ResultSet rs = st.executeQuery("SELECT * FROM bot_rumors")) {
                while (rs.next()) try {
                    rumors.add(new SocialData.RumorRow(UUID.fromString(rs.getString("bot_id")), UUID.fromString(rs.getString("rumor_id")), rs.getString("kind"),
                        rs.getString("subject"), rs.getString("where_text"), rs.getInt("confidence"), rs.getInt("hops"), rs.getLong("created_at")));
                } catch (IllegalArgumentException e) { log.warning("Skipping unreadable rumor row"); }
            }
            java.util.Map<UUID, List<UUID>> members = new java.util.HashMap<>();
            try (ResultSet rs = st.executeQuery("SELECT * FROM faction_members")) {
                while (rs.next()) try { members.computeIfAbsent(UUID.fromString(rs.getString("faction_id")), k -> new ArrayList<>()).add(UUID.fromString(rs.getString("bot_id"))); }
                catch (IllegalArgumentException e) { log.warning("Skipping unreadable faction member row"); }
            }
            try (ResultSet rs = st.executeQuery("SELECT * FROM factions")) {
                while (rs.next()) try {
                    UUID id = UUID.fromString(rs.getString("id"));
                    List<UUID> m = members.getOrDefault(id, List.of());
                    if (!m.isEmpty()) factions.add(new SocialData.FactionRow(id, rs.getString("name"), UUID.fromString(rs.getString("founder_id")), rs.getLong("created_at"), m));
                } catch (IllegalArgumentException e) { log.warning("Skipping unreadable faction row"); }
            }
        } catch (SQLException e) { throw new StorageException("Failed to load social data", e); }
        return new SocialData(rels, mems, rumors, factions);
    }

    private void saveSocialBlocking(SocialData d) {
        try {
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                for (String t : new String[] {"bot_relationships", "bot_memories", "bot_rumors", "factions", "faction_members"}) st.executeUpdate("DELETE FROM " + t);
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bot_relationships(bot_id,other_id,other_is_player,affinity,trust,familiarity,last_at) VALUES(?,?,?,?,?,?,?)")) {
                    for (SocialData.Rel r : d.relationships()) {
                        ps.setString(1, r.bot().toString()); ps.setString(2, r.other().toString()); ps.setInt(3, r.otherIsPlayer() ? 1 : 0);
                        ps.setDouble(4, r.affinity()); ps.setDouble(5, r.trust()); ps.setDouble(6, r.familiarity()); ps.setLong(7, r.lastAt());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bot_memories(bot_id,kind,subject_id,text,importance,at) VALUES(?,?,?,?,?,?)")) {
                    for (SocialData.Mem m : d.memories()) {
                        ps.setString(1, m.bot().toString()); ps.setString(2, m.kind());
                        if (m.subject() == null) ps.setNull(3, Types.VARCHAR); else ps.setString(3, m.subject().toString());
                        ps.setString(4, m.text()); ps.setInt(5, m.importance()); ps.setLong(6, m.at());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement ps = conn.prepareStatement("INSERT INTO bot_rumors(bot_id,rumor_id,kind,subject,where_text,confidence,hops,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                    for (SocialData.RumorRow r : d.rumors()) {
                        ps.setString(1, r.bot().toString()); ps.setString(2, r.rumorId().toString()); ps.setString(3, r.kind()); ps.setString(4, r.subject());
                        ps.setString(5, r.where()); ps.setInt(6, r.confidence()); ps.setInt(7, r.hops()); ps.setLong(8, r.createdAt());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                try (PreparedStatement pf = conn.prepareStatement("INSERT INTO factions(id,name,founder_id,created_at) VALUES(?,?,?,?)");
                     PreparedStatement pm = conn.prepareStatement("INSERT INTO faction_members(faction_id,bot_id) VALUES(?,?)")) {
                    for (SocialData.FactionRow f : d.factions()) {
                        pf.setString(1, f.id().toString()); pf.setString(2, f.name()); pf.setString(3, f.founder().toString()); pf.setLong(4, f.createdAt()); pf.addBatch();
                        for (UUID m : f.members()) { pm.setString(1, f.id().toString()); pm.setString(2, m.toString()); pm.addBatch(); }
                    }
                    pf.executeBatch(); pm.executeBatch();
                }
            }
            conn.commit();
        } catch (SQLException | RuntimeException e) {
            try { conn.rollback(); } catch (SQLException ignored) { /* original error matters more */ }
            throw new StorageException("Failed to save social data", e);
        } finally {
            try { conn.setAutoCommit(true); } catch (SQLException ignored) { /* closing anyway */ }
        }
    }

    private boolean deleteBlocking(UUID id) {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM bots WHERE id=?")) {
            ps.setString(1, id.toString());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new StorageException("Failed to delete bot " + id, e);
        }
    }

    private List<BotRecord> loadAllBlocking() {
        List<BotRecord> out = new ArrayList<>();
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM bots ORDER BY created_at")) {
            while (rs.next()) {
                String id = rs.getString("id");
                try {
                    out.add(read(rs));
                } catch (RuntimeException | SQLException e) {
                    // The row stays in the database untouched; it is simply not loaded this session.
                    log.log(Level.SEVERE, "Skipping unreadable bot row id=" + id, e);
                }
            }
        } catch (SQLException e) {
            throw new StorageException("Failed to load bots", e);
        }
        return out;
    }

    private static BotRecord read(ResultSet rs) throws SQLException {
        return new BotRecord(
            UUID.fromString(rs.getString("id")),
            rs.getString("name"),
            rs.getString("skin_spec"),
            LifecycleState.parse(rs.getString("state"), LifecycleState.INACTIVE),
            readLoc(rs, "world_uuid", "world_name", "x", "y", "z", "yaw", "pitch"),
            rs.getDouble("health"), rs.getInt("hunger"), (float) rs.getDouble("saturation"), rs.getLong("experience"),
            BotCodec.personalityFromJson(rs.getString("personality_json")),
            CombatSkill.parse(rs.getString("combat_skill"), CombatSkill.BEGINNER),
            CombatStyle.parse(rs.getString("combat_style"), CombatStyle.DEFENSIVE_SURVIVALIST),
            BotCodec.listFromJson(rs.getString("goals_json")),
            readLoc(rs, "home_world_uuid", "home_world_name", "home_x", "home_y", "home_z", "home_yaw", "home_pitch"),
            rs.getString("faction_id"),
            BotCodec.mapFromJson(rs.getString("inventory_json")),
            BotCodec.mapFromJson(rs.getString("extra_json")),
            rs.getInt("death_count"), rs.getLong("created_at"), rs.getLong("last_saved_at"), rs.getInt("record_version"));
    }

    private static BotLocation readLoc(ResultSet rs, String wu, String wn, String x, String y, String z, String yaw, String pitch) throws SQLException {
        String worldName = rs.getString(wn);
        if (worldName == null) return null;
        String uuid = rs.getString(wu);
        return new BotLocation(uuid == null ? null : UUID.fromString(uuid), worldName,
            rs.getDouble(x), rs.getDouble(y), rs.getDouble(z), (float) rs.getDouble(yaw), (float) rs.getDouble(pitch));
    }
}
