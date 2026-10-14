# MTREE: Living Players (Stages 1-6: foundation, movement, combat, survival, construction, social)

Persistent, player-looking AI characters for the MTREE SMP (Paper 1.21.11, Java 21).
**Honest status: this is the foundation, not the finished system.** Bots can be created, persisted, spawned near
players, walk/wander/follow, and fight hostile mobs in melee (and retaliate against a player who hits them). They also gather wood and stone, craft wooden then stone tools, and place a crafting table. They can build a small wooden hut from a blueprint. They also talk to players and each other, remember who helped or hurt them, share rumors and form factions. They cannot yet smelt, farm, add doors/glass or run a settlement - see
[FEATURE_STATUS.md](FEATURE_STATUS.md). **Nothing has been run on a Paper server yet, and `gradle build` has not been
run against the real Paper API** (see "Verification").

## Build
Requires JDK 21 and Gradle 8.10+ (no wrapper jar is shipped; generate one with `gradle wrapper`).
```
gradle build          # jar: build/libs/MTREELivingPlayers-0.6.0-stage6.jar
gradle test           # Bukkit-free core tests
```
Without Gradle/Paper (core logic only): `tools/run-core-tests.sh`. Type-check against API stubs: `tools/stub-check.sh`.

## Install
Drop the jar into `plugins/`, start once, edit `plugins/MTREELivingPlayers/*.yml`, `/mtreebots reload`.
First start downloads `sqlite-jdbc` via Paper's `libraries:` mechanism (needs internet once; Paper may already bundle it).
Nothing else is required. LuckPerms, SkinsRestorer, Geyser, Floodgate, ViaVersion, Essentials, Graves, HeadDrop,
LevelledMobs, ProtocolLib etc. are optional and only detected, never called.

## What bots are (important)
Bots are vanilla **Mannequin** entities (added in Minecraft 1.21.9) shown with a skin, name tag and equipment.
They are **not Players**: no tab-list entry, no player events (so grave/death-penalty plugins don't see them), no voice chat,
no real inventory/hunger (the plugin simulates those). The entity layer sits behind `BotEntityAdapter`, so a different
representation can replace it without touching behaviour code. See docs/API_VERIFICATION.md.

## Commands (`/mtreebots`)
| Command | Permission | Does |
|---|---|---|
| `help` | - | list what you may use |
| `spawn <name>` | spawn | spawn at your position; creates the bot if the name is new (rejects names of real players) |
| `spawnrandom <1-10>` | spawn | spawn at safe spots near players (respects all caps/safety) |
| `despawn <name>` | spawn | remove entity, keep identity |
| `remove <name> confirm` | manage | permanent delete; items are dropped, never destroyed silently |
| `list`, `info`, `inv`, `population`, `save` | manage | inspection / force save |
| `tp <name>` | manage | teleport to the bot (or its last position) |
| `home <name> [set\|clear\|show]` | manage | bot home = your position (persisted) |
| `faction list` | manage | list bot factions |
| `memory <name>` | manage | feelings, memories and rumors of a bot |
| `build <name> <blueprint\|status\|cancel>` | manage | start/inspect/cancel a construction project |
| `equip <name>` | manage | wear/hold the best gear the bot already owns |
| `give <name>` | manage | move the item in your main hand into the bot's inventory |
| `goto`, `follow`, `stop`, `resume <name>` | manage | walk to you / follow you / halt + pause wandering / resume wandering |
| `enable`, `disable <name>` | manage | disabled bots are never touched by automation |
| `reload` | reload | reload YAML |
| `debug [name\|toggle]` | debug | diagnostics, integration report, debug logging |

Permissions: `mtreebots.admin` (op; grants all), `.spawn`, `.manage`, `.debug`, `.reload`. Nothing is granted to players or bots automatically.

## Combat (Stage 3)
Melee only. Bots hit with what they own (fists if nothing - give them gear with `/mtreebots give`), at vanilla damage scaled by the
attack cooldown. They need reach, line of sight and to be facing the target; reaction time, turn rate and mistakes apply at every
skill level, so even an EXPERT is beatable. They fight hostile mobs, and fight a real player only in retaliation within
`retaliation-window-seconds` (or never: `attack-players: NEVER`). Wounded bots eat real food (consumed from their inventory) or retreat, and
only re-engage after recovering. Regeneration costs hunger; starvation is real. Weapons wear out and break.
Not implemented: shields, bows/crossbows, mace/wind charge, pearls, cobwebs, bot-vs-bot fights. See `combat.yml`.

## Survival and block safety (Stage 4)
Bots chop **natural** trees and mine **exposed** stone and coal, then craft planks, sticks, a crafting table and wooden/stone tools,
using only items they actually obtained. Drops come from the real block (`Block#getDrops(tool)`), mining takes vanilla time for the tool,
tools wear out, and a full inventory sheds only junk.

Because bots are not Players, claim plugins cannot be asked for permission, so the rules are deliberately strict (`BlockPolicy`, unit-tested):
- Only whitelisted natural blocks may be broken (stone, granite, diorite, andesite, coal ore, dirt, grass, sand, gravel, and logs of natural trees).
- Never within 3 blocks of anything that looks built (planks, bricks, glass, doors, chests ...), never inside `protected-regions` (worlds.yml).
- **If WorldGuard, GriefPrevention, Towny or Lands is installed, bots change NO blocks** unless you set `allow-with-claim-plugins: true` in survival.yml.
- The only block a bot places is a crafting table from its own inventory; it only picks up blocks recorded as bot-placed.
- Set `survival.enabled: false` (or `block-interaction.enabled: false`) to switch it all off. Recommended: add your spawn/builds to `protected-regions`.
Known gap: no BlockBreakEvent/BlockPlaceEvent is fired, so CoreProtect-style loggers will not record bot changes.

## Construction (Stage 5)
Blueprints are JSON files in `plugins/MTREELivingPlayers/blueprints/` (see `starter_hut.json`; invalid files are rejected with a console message).
A builder-type bot (or `/mtreebots build <bot> starter_hut`) picks a **level, natural, empty** site at least 3 blocks from anything player-made,
gathers the planks (18 logs for the hut), then places blocks at ~2 per second from a legal standing spot. It only ever places planks, only inside the
project box, only into empty cells or wild plants, and never where a claim plugin is installed (same strict default as Stage 4). Progress is saved every
8 blocks; a restart resumes the build. When finished, the hut becomes the bot's home. One house per bot by default.
Not yet: doors, glass, furniture, farms, roads, repairs, expanding settlements.

## Social life (Stage 6)
Bots chat in their own voice (from personality; some Taglish), greet players, comment on night/rain/finished builds, and answer when spoken to by name or when
close. What they understand is a **fixed list of intents** (greeting, farewell, thanks, insult, name, what-are-you-doing, where-are-you, follow me, stop, help/come here,
trade): keyword matching in English with a little Tagalog, NOT language understanding. They may refuse (busy, or they dislike you). Chat can only trigger the same
validated follow/stop/come-here actions as the commands. Location questions never reveal coordinates, and rumors carry only coarse directions.
They remember significant events (an attack outlives a hundred greetings), feelings fade over weeks, friends form **factions** automatically and help each other
(against mobs by default, not against real players unless you enable it). Bots never start fights with players: only retaliation does.
An OPTIONAL language model (`chat.yml: llm`, off by default) can suggest the TEXT of small-talk replies through a generic JSON endpoint; the key is read from an
environment variable, replies are sanitised, and every failure falls back to templates. Chat is rate-limited per bot and globally.

## Data safety
SQLite `bots.db` (WAL, synchronous=FULL), one IO thread, immutable snapshots, every save batch one transaction,
versioned migrations with a pre-migration copy, rolling `backups/`, corrupt files quarantined (never overwritten),
refuses to open a database newer than the plugin. If the DB can't load, the plugin disables itself rather than run without persistence.
Entities are non-persistent, tagged in PDC, and orphan-swept, so restarts and crashes don't duplicate bots.
Chunks are never force-loaded for bots.

## Known limitations
- Walking is velocity-driven on an entity with no AI; smoothness is untested (T2.1). No swimming, ladders, doors.
- Encounters are safe-location spawns near a player followed by short wanders, not long-distance travel yet.
- Overworld-type dimensions only. Wander/spawn use surface positions, no cave logic.
- "Player-built structure" detection in spawn safety is a block-name heuristic.
- Bedrock (Geyser) rendering of Mannequins, and reactions of head-drop / mob-levelling plugins to Mannequin deaths, are **unknown** until tested.
- Combat is melee-only; vanilla hostile mobs ignore Mannequins until a bot hits them. Damage/knockback on Mannequins is untested (T3.1).
- Survival is surface-only (no tunnelling), no smelting so no iron tier, no farming/storage. Block-logging plugins don't see bot block changes.
- Construction is one wooden blueprint (open doorway, no door/glass), no repair.
- Chat, factions, rumors, offline simulation: not implemented.

## Layout
`core/` config, integrations, debug · `entity/` adapter + Mannequin + skins · `bot/` identity, inventory, manager, lifecycle ·
`storage/` SQLite · `director/` population + spawn safety · `nav/` pathfinder + navigator · `protect/` protection ·
`combat/` melee AI, gear, hunger · `listener/` events · `command/` commands. Roadmap: Stage 3 (remaining: ranged/shield/mace/pearl), 4 survival/block interaction, 5 construction, 6 social, 7 living world, 8 integration/optimisation.
