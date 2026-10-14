package net.mtree.livingplayers.storage;

import java.util.List;

/** The ordered, append-only list of schema migrations. Never edit a released migration; add a new one. */
public final class Migrations {
    private Migrations() {}

    public static final List<Migration> ALL = List.of(
        Migration.ofStatements(1, "initial bots table",
            """
            CREATE TABLE bots (
              id TEXT PRIMARY KEY NOT NULL,
              name TEXT NOT NULL COLLATE NOCASE UNIQUE,
              skin_spec TEXT NOT NULL DEFAULT 'default',
              state TEXT NOT NULL,
              world_uuid TEXT, world_name TEXT, x REAL, y REAL, z REAL, yaw REAL, pitch REAL,
              health REAL NOT NULL, hunger INTEGER NOT NULL, saturation REAL NOT NULL DEFAULT 5,
              experience INTEGER NOT NULL DEFAULT 0,
              personality_json TEXT NOT NULL, combat_skill TEXT NOT NULL, combat_style TEXT NOT NULL,
              goals_json TEXT NOT NULL DEFAULT '[]',
              home_world_uuid TEXT, home_world_name TEXT, home_x REAL, home_y REAL, home_z REAL,
              home_yaw REAL, home_pitch REAL,
              faction_id TEXT,
              inventory_json TEXT NOT NULL DEFAULT '{}',
              extra_json TEXT NOT NULL DEFAULT '{}',
              death_count INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL, last_saved_at INTEGER NOT NULL,
              record_version INTEGER NOT NULL DEFAULT 1
            )""",
            "CREATE INDEX idx_bots_state ON bots(state)"),
        Migration.ofStatements(2, "bot-placed block registry",
            """
            CREATE TABLE bot_placed_blocks (
              world_uuid TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL,
              bot_id TEXT NOT NULL, material TEXT NOT NULL, placed_at INTEGER NOT NULL,
              PRIMARY KEY (world_uuid, x, y, z)
            )""",
            "CREATE INDEX idx_placed_bot ON bot_placed_blocks(bot_id)"),
        Migration.ofStatements(3, "construction projects",
            """
            CREATE TABLE construction_projects (
              id TEXT PRIMARY KEY NOT NULL, bot_id TEXT NOT NULL, blueprint TEXT NOT NULL,
              world_uuid TEXT NOT NULL, ox INTEGER NOT NULL, oy INTEGER NOT NULL, oz INTEGER NOT NULL,
              rotation INTEGER NOT NULL, materials_json TEXT NOT NULL DEFAULT '{}', progress_hex TEXT NOT NULL DEFAULT '',
              status TEXT NOT NULL, skipped INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_projects_bot ON construction_projects(bot_id)"),
        Migration.ofStatements(4, "social: relationships, memories, rumors, factions",
            """
            CREATE TABLE bot_relationships (
              bot_id TEXT NOT NULL, other_id TEXT NOT NULL, other_is_player INTEGER NOT NULL,
              affinity REAL NOT NULL, trust REAL NOT NULL, familiarity REAL NOT NULL, last_at INTEGER NOT NULL,
              PRIMARY KEY (bot_id, other_id)
            )""",
            """
            CREATE TABLE bot_memories (
              id INTEGER PRIMARY KEY AUTOINCREMENT, bot_id TEXT NOT NULL, kind TEXT NOT NULL, subject_id TEXT,
              text TEXT NOT NULL, importance INTEGER NOT NULL, at INTEGER NOT NULL
            )""",
            "CREATE INDEX idx_memories_bot ON bot_memories(bot_id)",
            """
            CREATE TABLE bot_rumors (
              bot_id TEXT NOT NULL, rumor_id TEXT NOT NULL, kind TEXT NOT NULL, subject TEXT NOT NULL, where_text TEXT NOT NULL,
              confidence INTEGER NOT NULL, hops INTEGER NOT NULL, created_at INTEGER NOT NULL,
              PRIMARY KEY (bot_id, rumor_id)
            )""",
            "CREATE TABLE factions (id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, founder_id TEXT NOT NULL, created_at INTEGER NOT NULL)",
            "CREATE TABLE faction_members (faction_id TEXT NOT NULL, bot_id TEXT NOT NULL, PRIMARY KEY (faction_id, bot_id))")
    );

    public static int latest() { return ALL.get(ALL.size() - 1).version(); }
}
