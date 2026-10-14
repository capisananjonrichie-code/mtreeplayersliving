# Paper 1.21.11 API verification record

Method: Paper's published Javadoc for `1.21.11` (jd.papermc.io/paper/1.21.11). The build sandbox could NOT reach
`repo.papermc.io`, so nothing here was compiled against the real `paper-api` jar.

## Verified against the 1.21.11 Javadoc
| API | Finding |
|---|---|
| `org.bukkit.entity.Mannequin` | Exists. `extends LivingEntity`. |
| Mannequin methods used | `setProfile(ResolvableProfile)`, `setImmovable(boolean)`, `setDescription(Component)` (null hides the text), `getEquipment()` |
| Inherited, used | `setPersistent`, `setRemoveWhenFarAway`, `setCanPickupItems`, `setInvulnerable`, `customName(Component)`, `setCustomNameVisible`, `getHealth/setHealth/getMaxHealth`, `hasLineOfSight(Location)`, `teleportAsync`, `getPersistentDataContainer`, `setVelocity`, `setRotation`, `isOnGround` |
| `ResolvableProfile.resolvableProfile(PlayerProfile)` | Exists (`io.papermc.paper.datacomponent.item`, marked `@Experimental`) |
| `EntityType.MANNEQUIN` | Present in the 1.21.9 / 1.21.10 enum pages; the 1.21.11 Mannequin page exists. Spawning uses `World#spawn(Location, Class, Consumer)` so the enum constant is not referenced directly. |

## Used from long-standing Paper/Bukkit API, NOT individually re-verified for 1.21.11
`Bukkit.getTPS`, `getMinecraftVersion`, `getOfflinePlayerIfCached`, `createProfile`, `PlayerProfile#update/setProperties/hasTextures`,
`ItemStack#serializeAsBytes/deserializeBytes`, `EntitiesLoadEvent/EntitiesUnloadEvent`, `World#getHighestBlockYAt(int,int,HeightMap)`,
`JavaPlugin#getPluginMeta`, `EntityEquipment#setDropChance`.
If any of these differ, `gradle build` fails with a precise message - fix there.

## Stage 3 additions, NOT individually verified for 1.21.11
`LivingEntity#damage(double, Entity)`, `swingMainHand`, `hasLineOfSight(Entity)`, `Entity#getNearbyEntities`, `getFallDistance`, `isInWater`,
`Bukkit#getEntity(UUID)`, `ItemMeta`/`org.bukkit.inventory.meta.Damageable`, `World#getDifficulty`, `org.bukkit.entity.Monster`.
Behaviour question (needs a server): does `damage(amount, mannequin)` apply knockback and the hurt animation like a mob attack? (T3.1)

## Stage 4 additions, NOT individually verified for 1.21.11
`Block#getDrops(ItemStack)`, `Block#setType`, `Block#getRelative`, `Block#getBlockData` + `org.bukkit.block.data.type.Leaves#isPersistent`,
`World#rayTraceBlocks(Location, Vector, double)` + `RayTraceResult#getHitBlock`, `World#getNearbyEntities(Location,x,y,z)`, `LivingEntity#getEyeLocation`.
Behaviour questions (server needed): does Leaves#isPersistent() reliably distinguish worldgen leaves from player-placed ones in Terralith trees? (T4.4)

## Stage 5 additions
No new Paper API beyond Stage 4 (`World#getHighestBlockYAt(int,int,HeightMap)`, `Block#setType`, `getNearbyEntities`, ray tracing). Site selection, blueprints, ordering and policy are pure and tested.

## Stage 6 additions, NOT individually verified for 1.21.11
`io.papermc.paper.event.player.AsyncChatEvent#getPlayer/message`, `PlainTextComponentSerializer.plainText().serialize`, `LivingEntity#getKiller`,
`World#getTime/hasStorm`, `BukkitScheduler#runTaskLater`. The language-model adapter uses only the JDK (`java.net.http`) and is tested against a local `com.sun.net.httpserver`.
Behaviour question (server needed): does AsyncChatEvent fire for every client incl. Bedrock via Geyser/Floodgate, and does it coexist with Essentials chat formatting? (T6.12)

## Isolation
All Mannequin/ResolvableProfile code is confined to `entity/MannequinAdapter` and `entity/SkinService`.
Everything else uses `entity/BotEntityAdapter` and `LivingEntity`. At startup the plugin checks that
`org.bukkit.entity.Mannequin` exists and disables itself with a clear message if not.

## What a Mannequin does NOT provide (so the plugin supplies it)
No pathfinding (-> `nav/`), no real inventory (-> `BotInventory`), no hunger, no Player events
(`PlayerDeathEvent` never fires for bots - handled via `EntityDeathEvent` in `BotEntityListener`).

## Unknown until a live server run (see TEST_PLAN.md)
Whether `setVelocity` moves a Mannequin smoothly (T2.1), whether `immovable=false` + gravity behave as assumed (T2.2),
whether damage(amount, bot) knocks the victim back (T3.1), how Geyser renders Mannequins for Bedrock (T8.1), whether head-drop / mob-levelling plugins react to Mannequin deaths (T8.3).
