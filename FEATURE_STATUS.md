# Feature status (Stages 1-6)

Legend
- **Implemented / unit-tested** - logic exists and its Bukkit-free core passed automated tests (175 tests, real SQLite, local HTTP server).
- **Implemented / needs server test** - code exists and type-checks against API stubs, but has NEVER run on Paper. See docs/TEST_PLAN.md.
- **Partial** - some of the requirement exists; the gap is stated.
- **Not implemented** - nothing exists yet (planned stage shown). Nothing here is a stub pretending to work.

## Build & verification status
| Item | Status |
|---|---|
| Core tests (`tools/run-core-tests.sh`) | **175 passed** in the author's sandbox (JDK 21, JUnit 5, sqlite-jdbc) |
| Whole source tree vs hand-written API stubs | **Passes** (type-check of our code only) |
| `gradle build` against real `paper-api:1.21.11` | **NOT RUN** - sandbox cannot reach repo.papermc.io. Run it first. |
| Any run on a Paper server | **NONE** |

## Spec section by section
| § | Requirement | Status | Notes |
|---|---|---|---|
| 2 | Optional integrations never required | Implemented / needs server test | Runtime presence detection only; zero third-party API calls; `softdepend` only orders load |
| 2 | Compatibility *claims* | Not claimed | Graves/HeadDrop/LevelledMobs/Geyser listed as UNVERIFIED in `/mtreebots debug` |
| 3 | Entity abstraction | Implemented / needs server test | `BotEntityAdapter` + `MannequinAdapter` (Mannequin API verified in Javadoc, not compiled) |
| 3 | Real player-grade entity | Not implemented (by decision) | Bots are Mannequins, not Players: not in tab list, no player events, no voice chat |
| 4 | Persistent UUID identity, SQLite, WAL, migrations, atomic batches, backups, corruption quarantine | **Implemented / unit-tested** | |
| 4 | Async DB, no Bukkit objects off-thread | Implemented / unit-tested (storage) | Snapshots are immutable `BotRecord`s |
| 4 | No duplicate entities (registry + PDC tag + non-persistent entities + orphan sweep) | Implemented / needs server test | T1.3, T1.4 |
| 4 | Saved: identity, skin, position, health, inventory/armor/offhand, xp, personality, goals, home, faction id, skill/style, timestamps, schema version | Implemented / unit-tested | Faction id is stored but unused until Stage 6 |
| 4 | Saved: bot-placed blocks | **Implemented / unit-tested** | Migration V2 |
| 4 | Saved: relationships, discovered locations, construction projects | Not implemented | Later stages add tables via migrations |
| 4 | Lifecycle states (active, inactive, unloaded, dead, respawning, disabled) | Implemented / unit-tested (state machine); needs server test (wiring) | |
| 5 | Population director: caps, per-world, cooldowns, distances, safety, no-view, TPS throttle, per-player scaling | Implemented / unit-tested (planner, safety, cooldowns, sampling); needs server test (world wiring) | |
| 5 | Camps, settlements, roads, patrols | Not implemented | Stage 7 |
| 5 | Encounters via real travel | Partial | Spawn at a safe encounter location, then Stage 2 wandering. Bots do not yet travel long distances toward players |
| 6 | Pathfinding: bounded A*, step-up, safe drops, water/lava/hazard/unloaded-chunk avoidance, no corner cutting | **Implemented / unit-tested** | |
| 6 | Walking, jumping, stuck detection, replan, wander, follow, player avoidance | Implemented / needs server test | Velocity-driven; T2.1-T2.6 |
| 6 | Swimming, climbing (ladders/vines), doors, sprint decisions beyond distance, escaping | Not implemented | |
| 7 | Personality traits + archetypes; long-term goals chosen from personality | Implemented / unit-tested | Goals are **data only** - nothing acts on them yet (Stage 7) |
| 7 | Goal selector, task scheduler, interruption/resume | Not implemented | Stage 3-7 |
| 8 | Combat skill + style assigned (8 styles, 4 skills) | **Implemented / unit-tested** (selection, skill tables, brain) | Style shifts retreat threshold, strafing and weapon preference |
| 8 | Melee: sword / axe / fists with vanilla damage, attack cooldown, sprint-vs-crit rule, jump-crit attempts | Core **unit-tested**; world behaviour needs server test | Damage applied via `LivingEntity#damage(amount, bot)`; knockback/hurt animation UNVERIFIED (T3.1) |
| 8 | No omniscience: reaction delay, turn-rate limit, facing tolerance, mistake chance at EVERY skill, line-of-sight required | Core **unit-tested** (all skills fallible, monotonic improvement) | |
| 8 | Target selection, group awareness (flee when outnumbered), retaliation-only vs real players, fights hostile mobs | Core **unit-tested**; needs server test | Note: vanilla mobs do not target Mannequins, so they only fight a bot after being hit |
| 8 | Eating, healing, retreat, re-engage after recovery (hysteresis) | Core **unit-tested**; needs server test | Healing consumes real items + hunger; no free healing (tested) |
| 8 | Hunger, regeneration, starvation | **Implemented / unit-tested** | Sprint exhaustion is inferred from distance moved |
| 8 | Equipment switching (best weapon for style, best armour per slot, from owned items only) | Implemented / needs server test; scoring **unit-tested** | |
| 8 | Weapon durability and breakage | Implemented / needs server test | Unbreaking/Mending/enchantments ignored (partial) |
| 8 | Shield use/disabling, bows & crossbows, projectile dodging, mace + wind charge, ender pearls, cobwebs, water-bucket clutch, armour-enchant evaluation, bot-vs-bot combat | **Not implemented** | Shields need an item-use API not yet verified; others are later Stage 3 work |
| 9 | Gathering: chop natural trees, mine exposed stone + coal; collect drops | Rules **unit-tested**; world behaviour needs server test | Drops come from `Block#getDrops(tool)` of the REAL block; stone gives nothing without a pickaxe. No tunnelling: only blocks reachable from walkable ground |
| 9 | Crafting: planks, sticks, crafting table, wooden + stone pickaxe/axe/sword, furnace recipe | **Implemented / unit-tested** | Ingredients are consumed, results added (fuzz-tested conservation). Needs a table within 4 blocks, which the bot places from its own inventory |
| 9 | Tool progression wood -> stone via a planner | **Implemented / unit-tested** (simulated from an empty inventory) | Iron and above need smelting: **not implemented** |
| 9 | Smelting, cooking, farming/harvesting crops, animals, collecting dropped items, storage, repairing | **Not implemented** | Stage 7 (and smelting still to come) |
| 9 | Inventory management: discard junk when full, never valuables | **Implemented / unit-tested** | Overflow drops into the world, nothing is deleted |
| 10 | Block breaking: policy gate (twice), reach, line of sight, vanilla break time, tool choice, tool wear, interruption handling | Policy + timing **unit-tested**; world behaviour needs server test (T4.x) | No BlockBreakEvent is fired (bots are not Players): block loggers (CoreProtect) will not see it |
| 10 | Block placing: crafting table only, from inventory, support + collision + entity checks | Implemented / needs server test | Placed blocks are recorded in the DB (migration V2) so bots only pick up their own |
| 10 | Protection: whitelist of natural blocks, natural-tree check (non-persistent leaves), no player-made block within 3, protected regions, **denied entirely when WorldGuard/GriefPrevention/Towny/Lands is installed** (unless the admin opts in) | Decision logic **unit-tested**; scans need server test | Default-deny: cannot be verified -> refused |
| 11 | Blueprint format (JSON, validated), rotation, bill of materials, bottom-up build order, `starter_hut` (5x5, 3-high, doorway, 2 window gaps, roof, 69 planks) | **Implemented / unit-tested** | |
| 11 | Site selection: level natural ground, empty volume, no player-made block within 3, no hazards, loaded chunks only, bounded search | **Implemented / unit-tested** (selector logic); world probing needs server test | Wild plants in the volume are replaced; crops/torches/signs count as "someone's stuff" and reject the site |
| 11 | Material gathering for a build (logs -> planks via the survival planner) | Planner **unit-tested**; needs server test | 69 planks = exactly 18 logs (tested) |
| 11 | Incremental, rate-limited placement with real planks, from a legal standing spot (never on a cell still to be built) | Policy + order **unit-tested**; needs server test (T5.x) | 1 block per `place-interval-ticks` |
| 11 | Persistent projects: progress saved every 8 blocks, resumes after restart | DB round-trip **unit-tested**; end-to-end needs server (T5.4) | Migration V3 |
| 11 | Auto-start only for builders (building preference or build goal), after the tool ladder, max 1 house per bot | Implemented / needs server test | Goals now actually influence a decision |
| 11 | Doors, glass, windows with panes, furniture, farms, storage rooms, watchtowers, bridges, walls, roads, repairing/expanding structures | **Not implemented** | Need smelting/more recipes (glass), doors' block data, Stage 7 settlements |
| 12-13 | Farming, trade, settlements, exploration, discoveries | **Not implemented** | Stage 7 |
| 14 | Chat: per-bot voice (terse/gruff/enthusiastic, Taglish-or-English), tone from feelings, greetings/farewells, replies to players, bot-to-bot talk, ambient comments (night, rain, finished hut, tools, wounded, friend died), message cooldowns + global anti-spam cap, typing delay | Dialogue/limiter/sanitizer **unit-tested**; delivery needs server test | Vanilla-style `<Name> text` lines to players within `range` (not real chat packets/signed) |
| 14 | Defined intents: greeting, farewell, thanks, insult, name, status, location, follow, stop, help, trade (+ small talk) | Classifier **unit-tested** | Keyword matching, English + a little Tagalog. **Not language understanding**; unusual phrasing is misread. Location questions never reveal coordinates (tested). Trade is recognised and politely refused |
| 14 | Actions from chat (follow/stop/come here) | Compliance **unit-tested**; needs server test | Same validated navigator calls as commands; bot may refuse (busy/dislike). Chat text never triggers anything else |
| 14 | Bounded memory of significant events (fades by importance and age) | **Implemented / unit-tested** | Persisted (migration V4) |
| 14 | Optional language model adapter (off by default, endpoint in config, key from an env var, timeout/rate-limit/bad-response safe, reply-text only, sanitised, falls back to templates) | Adapter **unit-tested against a local HTTP server** (success, 500, 429, bad JSON, oversize, timeout, self rate-limit, dead server) | Never run against a real model; needs a small proxy for vendor APIs |
| 15 | Relationships (affinity/trust/familiarity -> hostile/rival/neutral/friendly/friend), personality-scaled reactions, decay, retaliation memory | **Implemented / unit-tested**, persisted | Players and bots alike |
| 15 | Reputation changes from actions (gifts, attacks, insults, thanks, deaths) | Implemented / needs server test | A bot's feelings toward a player change how it talks and whether it follows/helps; they do NOT make it attack players (only retaliation does) |
| 15 | Factions: autonomous formation (mutual liking + group-minded), joining, leaving, handover, dissolve, persisted | **Implemented / unit-tested** | `/mtreebots faction list` |
| 15 | Help and rescue: faction mates nearby help when a member is attacked | Implemented / needs server test | Against mobs only by default; vs players only if `assist-against-players: true` |
| 15 | Territory, territorial conflicts, expeditions, group combat tactics, shared goals | **Not implemented** | Stage 7 |
| 16 | Death handling | Implemented / needs server test | Drops inventory once or keeps it (config), respawn timer, deaths counted, no PlayerDeathEvent. Equipment progression: Not implemented |
| 17 | Rumors: coarse unreliable hearsay passed between bots (confidence drops, place can get muddled, never coordinates) | **Implemented / unit-tested** | Sources today: bot deaths. Discoveries/threats/Dynamic_World_Events: Stage 7 |
| 17 | Reactions to events (attacks, deaths, building done, tools ready, night, rain) | Implemented / needs server test | Raids/dangerous mobs/settlement threats/resource shortages: not implemented |
| 18 | Offline simulation | Not implemented | Stage 7. Offline bots simply stay parked; chunks are never loaded for them |
| 19 | Performance limits | Implemented / needs server test | Active cap, per-cycle attempt budget, path-calc budget, no chunk loading, async IO |
| 20 | Config files | **Implemented** | All 10 files are read and range-checked; every key in them is used by code (audited) |
| 21 | Commands | Implemented / needs server test | Present: help faction memory build spawn spawnrandom despawn remove list info tp home give inv equip enable disable goto follow stop resume population save reload debug. **Absent on purpose:** goal (no goal-assignment behaviour yet) (no behaviour behind them yet) |
| 22 | Architecture | Implemented | Package layout in README |
| 23 | Gradle build, plugin.yml, tests | Implemented, build **not run** | |
| 25 | Acceptance tests | See table below | |

## Acceptance tests (§25)
| # | Test | Status |
|---|---|---|
| 1 | Loads on Paper 1.21.11 / Java 21 | Needs server (T1.1) |
| 2 | Create, save, restore bot | DB round-trip **unit-tested**; end-to-end needs server (T1.2/T1.3) |
| 3 | No duplicates after restarts | DB part **unit-tested** (5x reopen); entity part needs server (T1.3/T1.4) |
| 4 | Encounter distance/population limits | Math **unit-tested**; world behaviour needs server (T1.5) |
| 5 | Navigate and recover | Pathfinding/stuck logic **unit-tested**; walking needs server (T2.1-T2.3) |
| 6 | Select and engage a valid target | Selection **unit-tested**; engagement needs server (T3.1) |
| 7 | Retreat and use food | Decision logic + hunger model **unit-tested**; needs server (T3.2/T3.3) |
| 8 | Gather a resource and keep it | Rules **unit-tested**; needs server (T4.1) |
| 9 | Place and break permitted blocks without duplicating items | Policy + conservation **unit-tested**; needs server (T4.3, T4.5) |
| 10 | Build a small blueprint using real materials | Materials/order/policy **unit-tested**; needs server (T5.1-T5.3) |
| 11 | Interrupted construction resumes after restart | Persistence **unit-tested**; needs server (T5.4) |
| 12 | Relationships, homes, goals persist | **Unit-tested** (homes, goals, relationships, memories, rumors, factions all round-trip through a real DB and a restart); end-to-end needs server |
| 13 | Missing optional plugins OK | Needs server (T1.6) |
| 14 | Missing AI service OK | Core never needs it; failures/timeouts fall back to templates (**unit-tested** adapter) |
| 15 | Death doesn't trigger penalties/graves | Needs server (T1.8) |
| 16 | Java + Bedrock players join normally | Needs server (T8.1) |
| 17 | Performance limits | Limits implemented; profiling needs server (T2.4) |
| 18 | Commands implemented + permission-checked | Needs server (T1.9) |

## Source manifest
Pure core (compiled & tested in sandbox): `bot/{LifecycleState,Trait,Personality,PersonalityTemplate,PersonalityGenerator,CombatSkill,CombatStyle,LongTermGoal,NameRules,SkinSpec,BotLocation,BotRecord,BotCodec}`, `storage/{BotStorage,Migration,Migrations,PlacedBlock,ProjectRecord,SocialData,StorageException}`, `nav/{Pathfinder,StuckDetector}`, `social/{Stance,SocialEvent,Relationship,RelationshipModel,MemoryStore,Rumor,RumorBook,FactionRegistry,FactionFormation,SocialState}`, `chat/{Intent,IntentClassifier,Compliance,ChatLimiter,ReplySanitizer,ActivitySummary,DialogueEngine,LanguageModelAdapter,HttpLanguageModelAdapter}`, `building/{Blueprint,BlueprintParser,ProgressBits,SiteSelector,BuildPolicy}`, `survival/{BreakTime,Recipes,SurvivalPlanner,InventoryPlanner}`, `protect/BlockPolicy`, `combat/{WeaponStats,AttackCooldown,EquipmentEvaluator,FoodTable,HungerModel,SkillProfile,CombatBrain,TargetSelector}`, `director/{BlockClass,TerrainProbe,BlockNames,SpawnSafety,PopulationPlanner,EncounterTracker,Annulus}`, `core/Settings`.

Bukkit layer (type-checked against stubs only): `MTREELivingPlayersPlugin`, `bot/{BotInventory,BotIdentity,BotMapper,BotManager,BotLifecycleManager}`, `entity/{BotEntityAdapter,MannequinAdapter,SkinService}`, `director/{BotPopulationDirector,BukkitTerrainProbe}`, `nav/BotNavigator`, `combat/{CombatAI,BotEquipmentService,BotVitalsService}`, `survival/{SurvivalAI,BlockInteractionService,BotPlacedBlocks}`, `building/{BuildingAI,BlueprintManager}`, `social/SocialService`, `chat/{BotChat,ConversationService}`, `listener/ChatListener`, `protect/ProtectionService`, `listener/BotEntityListener`, `command/{BotCommand,BotDebugCommand}`, `core/{ConfigService,IntegrationManager,Msg,Debug}`.
