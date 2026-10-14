# Paper-server test procedure

Everything below REQUIRES a running Paper 1.21.11 server. None of it has been run. Mark results in the Result column.
Setup: fresh test server, only this plugin, `/op` yourself. Build: `gradle build` -> `build/libs/MTREELivingPlayers-*.jar`.

| ID | Acceptance # | Steps | Pass criteria | Result |
|---|---|---|---|---|
| T1.1 | 1 | Start server with the jar | Log: "MTREE Living Players ready (representation: mannequin, schema v1)". No stack traces. | |
| T1.2 | 2 | `/mtreebots spawn Tala`, then `/mtreebots give Tala` holding 5 oak logs | Bot appears with name tag; info/inv show the logs | |
| T1.3 | 2,3,12 | Stop server, start again, repeat 5x | After each start: `/mtreebots list` shows exactly ONE Tala; exactly one mannequin in the world (`/execute as @e[type=mannequin] run say x`); inventory, home, goals unchanged | |
| T1.4 | 3 | Kill server process (`kill -9`) mid-run, restart | No duplicate entity; bots.db opens (check log for quarantine message - there must be none) | |
| T1.5 | 4 | `/mtreebots spawnrandom 3` with 1 player, defaults | Bots appear 48-96 blocks away, out of line of sight; never beyond `max-active-bots` | |
| T1.6 | 13 | Remove all optional plugins; start | No errors referencing them | |
| T1.7 | - | Delete the world a bot was in (or rename) then start | Warning "waiting: its world is not loaded", plugin keeps running; bot returns when world is back | |
| T1.8 | 15 | Install Graves + HeadDrop; kill a bot with a sword | No grave created; note any head drop; items drop once; `/mtreebots info` shows RESPAWNING | |
| T1.9 | 18 | Run every command in `/mtreebots help` as op and as a player without permissions | Op: works. No-perm: "You don't have permission" | |
| T2.1 | 5 | `/mtreebots goto Tala` from 30 blocks away | Bot walks (no teleport), jumps 1-block steps, arrives within ~3s/10 blocks | |
| T2.2 | 5 | Build a 3-block-high wall with no gap between bot and you, `goto` | Bot reports failure (`/mtreebots debug Tala` -> navigation: FAILED), does not jitter forever | |
| T2.3 | 5 | Block its route mid-walk with blocks | It replans (debug shows repaths) or gives up after `repath-limit` | |
| T2.4 | 17 | Spawn 4 bots, run Spark profiler 5 min | No visible tick cost from nav; `max-path-calcs-per-tick` respected | |
| T2.5 | - | Leave a bot idle 2 min | It wanders in small trips with pauses; never walks into water/lava; keeps >12 blocks from you | |
| T2.6 | - | `/mtreebots follow Tala`, run away, `/mtreebots stop Tala` | Follows at ~3 blocks, stops on command | |
| T3.1 | 6 | Give a bot `/mtreebots give` a stone sword. Spawn a zombie 8 blocks away (`/summon zombie`) | Bot walks up and swings; zombie takes damage and is knocked back; hurt animation plays; bot does not hit instantly or through walls. Record any "hit but no knockback" | |
| T3.2 | 7 | Bot with cooked beef; let a zombie wound it below ~45% | Bot backs off, stands still ~1.6s, beef count drops by exactly 1, hunger rises; it re-engages only after recovering above its re-engage threshold | |
| T3.3 | 7 | Same with NO food | Bot retreats (does not heal for free), `debug <name>` shows RETREATING, hunger/regen behaviour matches vanilla (regen only while hunger >= 18) | |
| T3.4 | 6 | Spawn 4+ zombies near an intermediate bot | Bot flees when outnumbered (> max-opponents) instead of fighting to the death | |
| T3.5 | 6 | Hit a bot with your fist/sword | Bot retaliates after its reaction delay; stops ~30s later. With `attack-players: NEVER` it never fights back | |
| T3.6 | 6 | Stand next to an idle bot for 5 min without hitting it | It never attacks you | |
| T3.7 | 6 | Give a bot an iron chestplate + diamond sword + stone sword, `/mtreebots equip` | It wears the chestplate and holds the diamond sword; inventory counts unchanged (nothing created) | |
| T3.8 | 6 | Fight with a stone sword for 200+ hits (creative-mode dummy mob) | Sword durability drops and it eventually breaks; bot falls back to the next best weapon/fists | |
| T3.9 | 15 | Kill a bot mid-fight | Inventory drops once; no duplicate loot; state RESPAWNING; it returns after `respawn-delay-seconds` | |
| T4.1 | 8 | Fresh bot (no items) in a forest, defaults, no claim plugin | Within a few minutes it chops logs (bare-handed ~3 s each), crafts planks/sticks, PLACES a crafting table, crafts wooden pickaxe + axe; `inv` shows consistent counts | |
| T4.2 | 8 | Continue to a stone outcrop within 10 blocks | It mines STONE only after owning a pickaxe, collects cobblestone (not nothing), crafts stone tools; `debug` shows `survival: PLAN ... tool ladder complete` | |
| T4.3 | 9 | Count items before/after one log is mined and one craft is done | Exactly the block's drops appear, exactly the recipe's ingredients vanish; no item entities left behind unless the inventory was full | |
| T4.4 | 10 | Build a small plank house (or log cabin with player-placed leaves) in a forest; put a bot nearby | Bot never breaks any block of it or within 3 blocks of it; check the log for `skips ... player-made structure nearby` | |
| T4.5 | 10 | Install WorldGuard (or GriefPrevention) | Bots stop touching ALL blocks (debug: `claim plugin 'WorldGuard' is installed`). With `allow-with-claim-plugins: true` they resume (accepting the risk) | |
| T4.6 | 10 | Add a `protected-regions` cuboid over a tree/stone | Bots never break blocks inside it | |
| T4.7 | 9 | Fill the bot's inventory with dirt/gravel then let it work | It drops junk (dirt above 32 etc.) to make room; valuables are never dropped | |
| T4.8 | 9 | Start a fight while it is mining | Mining stops, combat takes over, and mining resumes afterwards with the pickaxe re-selected | |
| T4.9 | 3,12 | Bot places a table, restart server twice | Table stays; after restart the bot may pick it up (it is in `bot_placed_blocks`); a player's own table is never broken | |
| T4.10 | 17 | 4 bots gathering, Spark profile 5 min | No visible tick cost; block scans limited by `scan-budget-blocks` | |
| T5.1 | 10 | `/mtreebots build <bot> starter_hut` with the bot in a clearing; no claim plugin | Bot finishes its tool ladder if needed, gathers 18 logs worth of planks, then places the hut ~2 blocks/s. Count: 69 planks placed, planks consumed = placed | |
| T5.2 | 10 | Compare the finished hut with `starter_hut.json` | Layout matches (door gap 2 high, lintel, 2 window gaps, roof). Try several runs: rotation varies | |
| T5.3 | 10 | Put a player-built wall / crop field / torch within 3 blocks of the clearing | Site is rejected (`no suitable flat, natural site...`) or a different site is chosen; nothing of yours is touched | |
| T5.4 | 11 | Stop the server (and `kill -9` once) while the hut is half built; restart | Project resumes (`Resuming 1 construction project(s)`), already-placed blocks are recognised, the rest is finished; no duplicate walls | |
| T5.5 | 10 | Drop a stone block into the footprint mid-build | The cell is skipped (`skipped`), never overwritten; > `max-skipped-blocks` abandons the project | |
| T5.6 | 6 | Start a fight mid-build | Building pauses, combat runs, building resumes | |
| T5.7 | 10 | `/mtreebots build <bot> cancel` | Project becomes ABANDONED, already placed blocks stay | |
| T5.8 | 10 | Install a claim plugin | Placement is denied (`claim plugin ... is installed`), project waits/backs off | |
| T5.9 | 12 | Finished hut | `info <bot>` shows a home inside the hut | |
| T6.1 | 14 | Walk up to a bot, say its name + "hello" | After ~1-3 s it replies in its own voice; relationship becomes known (`/mtreebots memory <bot>`) | |
| T6.2 | 14 | Say "<bot> follow me", then "<bot> stop" | It follows (or refuses with a reason if busy/dislikes you), then stops; `/mtreebots follow` and chat do the same thing | |
| T6.3 | 14 | Ask "where are you" / "what are you doing" | Vague location answer with NO numbers; honest activity answer | |
| T6.4 | 14 | Say "wanna trade?" | Polite refusal (trading not implemented) | |
| T6.5 | 14 | Spam 20 messages at a bot in a minute | Replies stop at `global-max-per-minute`; no chat flood from bots | |
| T6.6 | 15 | Hit a bot (or give it items with `/mtreebots give`) | Feelings drop (attack) or rise (gift) per `/mtreebots memory`; after an attack it will not follow you; it does NOT start hunting you | |
| T6.7 | 15 | Give 2+ bots time together near each other (30+ min), friendly personalities | Affinity grows; they form a faction (`/mtreebots faction list`), log line "formed the faction" | |
| T6.8 | 15 | Hit a faction member with zombies near | Mates within 24 blocks help vs mobs; vs YOUR hits they do not (default) | |
| T6.9 | 17 | Kill a bot in front of its friend | Friend remembers the killer, says something, gains a death rumor; rumors list contains no coordinates | |
| T6.10 | 12 | Restart the server | Relationships, memories, rumors, factions are back (`memory`, `faction list`) | |
| T6.11 | 14 | `llm.enabled: true` with an endpoint that is down | Bots still answer via templates; no errors beyond a debug line; with a working proxy only small-talk text changes | |
| T6.12 | 14 | Install a chat plugin / chat formatter (e.g. via Essentials chat) | Players' own chat is untouched; note whether bot lines look right | |
| T8.1 | 16 | Join with Bedrock via Geyser/Floodgate while a bot is nearby | Record: is the bot visible? skin? name tag? Java and Bedrock players can still join/play normally | |
| T8.2 | - | SkinsRestorer/LuckPerms installed | Permissions via LuckPerms group work for the `mtreebots.*` nodes | |
| T8.3 | 15 | LevelledMobs installed | Note whether Mannequins get levelled/renamed; if so, exclude entity type `MANNEQUIN` in LevelledMobs' own config | |
| T8.4 | 11,12 | Set `skins.mode: pool` with `player:<name>` | Skin appears after a short delay; offline Mojang API -> default skin, bot still spawns | |
