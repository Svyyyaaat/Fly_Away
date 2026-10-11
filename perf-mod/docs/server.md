# Server area: integrated-server tick (Server thread, spawning, saving)

Mixin areas: `vanilla.server` (common side, in `flyaway_perf.vanilla.mixins.json` "mixins") and
`compat.chunkactivitytracker` (in `flyaway_perf.compat.mixins.json` "mixins"). Helpers:
`dev.flyaway.perf.vanilla.server.SpawnerLookupCache`, `dev.flyaway.perf.compat.ChunkActivitySaveQueue`.
No `sable` mixins (see rejected ideas).

## Data and its limits

* `config/spark/profile-2026-06-17_02.02.43.sparkprofile`: Server thread only, 60 s, 1200 ticks, Java (safepoint
  biased) sampler at 4 ms. Busy ~24% (`IntegratedServer.tickServer`), the rest is `waitUntilNextTick`. MSPT in that
  window: median 7.8 ms, max 217 ms (last 5 min: max 453 ms, p95 57 ms, TPS 18.4). Only ~120 entities, 490 chunks.
  Read it with `java -Dthread="Server thread" ... Tree <profile> 0.05` (tools in `perf-mod/tools/spark`).
* **The mod set has changed a lot since June.** Every class was checked against the current `mods/`:
  - gone now: Twilight Forest (its `gatherPotentialSpawns` listener was 0.30% inside the spawner), Polytone
    (`ClientFrameTicker.onTick` ran an *uncached* noise-biome sample on the server thread, 0.43%), Does It Tick,
    Draconic Evolution, Accessories, Distant Horizons, Ars Nouveau, Critters and Companions;
  - new now: **ServerCore** (its `optimizations.tickets` mixins already fix the biggest part of the old spawner
    cost: `ChunkGenerator.getMobsAt -> StructureManager.getAllStructuresAt -> getChunk(STRUCTURE_REFERENCES,
    load=true)` added a `DistanceManager` ticket per spawn attempt, 0.47%; ServerCore now uses `getChunkNow`;
    same for the `isInNetherFortressBounds` block lookup), Immersive Portals, Voxy, VSS, micimmptl.
  - So the profile is used for the vanilla hot paths that are certainly still there (natural spawning, chunk
    saving), and static analysis of the current jars for the rest. A fresh Server-thread profile of the current pack
    would be very valuable (`/spark profiler start --thread "Server thread" --timeout 300`, include an autosave).

Remaining server-thread profile (current-pack relevant parts, % of the server thread):

| what | % | status |
|---|---|---|
| `NaturalSpawner.spawnForChunk` | 6.64 | patches 1 + 2 (vanilla mechanics stay) |
| of which `getRandomSpawnMobAt -> level.getBiome` | 2.17 | patch 1 (chunk-lookup part, 0.88) |
| of which `canSpawnMobAt -> mobsAt` | 0.58 | patch 2 (group-first duplicate) |
| of which `getAllStructuresAt` ticket churn | 0.47 | fixed by ServerCore (tickets) |
| entity ticking (`EntityTickList.forEach`) | 4.61 | generic movement/AI, nothing wasteful found |
| Sable `SubLevelPhysicsSystem` | 2.33 | 1.77 native Rapier step, rest small (rejected) |
| Architectury `EventFactory` unreflect | 2.08 | fixed by the lead (`compat.architectury`) |
| chunk autosave (c2me idle-time autosave) | 1.5 | runs in idle time, not in the tick |
| player tick (+ advancement TICK trigger 0.21) | 1.5 | gameplay, not touched |

---

## Patches

### 1. `vanilla.server.NaturalSpawnerBiomeMemoMixin`: spawner biome lookups without per-attempt chunk resolution
* **Target**: `net.minecraft.world.level.NaturalSpawner`
  - `@WrapMethod spawnCategoryForPosition(MobCategory, ServerLevel, ChunkAccess, BlockPos, SpawnPredicate, AfterSpawnCallback)`
    opens/closes a scope (try/finally);
  - `@WrapOperation` of `ServerLevel.getBiome(BlockPos)` in `getRandomSpawnMobAt(...)` and `mobsAt(...)` (require 2).
  - Mixin priority 900.
* **Problem**: `getRandomSpawnMobAt` 3.95% of the server thread, 2.17% of it in `level.getBiome(pos)`. 0.88% of that
  is `Level.getChunk(cx, cz, BIOMES, false)`: the ServerChunkCache main-thread path with c2me's `instrumentGetChunk`
  wrapper, `getChunkFutureMainThread`, c2me's `getManagedLevel` synchronized map and the flowsched holder map. It
  runs for every spawn attempt (plus 0.27% more inside `canSpawnMobAt -> mobsAt`). The 4-entry chunk cache of
  `ServerChunkCache` keeps missing because the spawner alternates BIOMES and FULL status lookups across neighbour
  chunks. All attempts of one `spawnCategoryForPosition` call random-walk within about +-15 blocks of one start
  position, so they touch 1 to 4 chunks.
* **Fix**: while a `spawnCategoryForPosition` call runs on the server thread, those `getBiome` calls are served by
  `SpawnerLookupCache`. It uses the level's own `BiomeManager.withDifferentSource(cache)`, so the zoom seed and the
  fuzz are the same, and c2me's native `getBiome` overwrite still applies. Its noise source is an exact copy of
  `LevelReader.getNoiseBiome` (`getChunk(cx,cz,BIOMES,false)` -> `chunk.getNoiseBiome`, otherwise
  `getUncachedNoiseBiome`). It only remembers the **full** chunks that this call returned (`LevelChunk` /
  `ImposterProtoChunk`, whose biome containers are fixed for the chunk's lifetime). Absent and proto chunks are
  looked up again every time. The memo is dropped when the call ends, so the chunk objects, biome holders and spawns
  are the same.
* **Threading**: the scope is only activated when `level.getServer().isSameThread()`. Every accessor re-checks the
  owner thread and the level, and falls back to the vanilla call otherwise. A recursive or nested spawner call just
  resets the scope. The remainder of the outer call then runs uncached.
* **Expected gain**: most of the ~1% of the server thread spent resolving chunks for spawner biome lookups (about
  4% of the busy tick time). It is small but free. The `PalettedContainer` read itself remains.
* **Compatibility**: ServerCore's optional `optimizations.biome_lookups` `@Redirect` on the same call is `require = 0`
  and is OFF in this pack (`fast-biome-lookups: false`). If someone enables it, our wrapper is applied first and the
  exact lookup wins (that option is not gameplay-neutral anyway). Other injectors in `NaturalSpawner` are unaffected:
  artifacts and collective in `spawnCategoryForPosition`, BOMD at `isValidSpawnPostitionForType` RETURN, YUNG's
  Better Fortresses at `isInNetherFortressBounds` HEAD, and ServerCore's `spawnForChunk` category interval.
* **Risk**: low. Exactness relies on vanilla's own lookup being used for everything that is not memoised.
* **Disable**: `mixin.vanilla.NaturalSpawnerBiomeMemoMixin=false`

### 2. `vanilla.server.NaturalSpawnerMobsAtMemoMixin`: don't build the same `mobsAt` list twice
* **Target**: `NaturalSpawner`, with the same `@WrapMethod` scope on `spawnCategoryForPosition`, plus:
  - `@WrapOperation` of `mobsAt(...)` in `getRandomSpawnMobAt(...)`, which remembers the result;
  - `@WrapOperation` of `mobsAt(...)` in `canSpawnMobAt(...)`, which reuses it.
* **Problem**: for the first attempt of every spawn group, vanilla calls `getRandomSpawnMobAt(pos)` ->
  `mobsAt(level, sm, gen, cat, pos, level.getBiome(pos))`. Then, with only RNG calls in between, it calls
  `isValidSpawnPostitionForType(pos)` -> `canSpawnMobAt` -> `mobsAt(level, sm, gen, cat, pos, null)`. That second
  call rebuilds the identical list:
  - the biome lookup;
  - the NeoForge nether-fortress check (YUNG's Better Fortresses adds a registry lookup and a `getStructureAt`);
  - `ChunkGenerator.getMobsAt`, which scans structure references and runs piece/bounding-box tests for every
    structure with spawn overrides (this pack has about 40 structure mods, plus Moog's Structure Lib's HEAD hook);
  - the `LevelEvent.PotentialSpawns` post.

  `canSpawnMobAt -> mobsAt` costs 0.58% of the server thread.
* **Fix**: the list from `getRandomSpawnMobAt` is kept as a single-use memo. The next `canSpawnMobAt` returns it if
  level, category and block position (`asLong`) are identical. Any other `canSpawnMobAt` drops the memo, the next
  `getRandomSpawnMobAt` replaces it, and the end of the call clears it. A mob is only created after `canSpawnMobAt`
  returned, so no spawn and no world change can happen between producing and consuming the memo.
  `PotentialSpawns` has no listener in the current pack (checked every jar: only Puzzles Lib's adapter, and no mod
  uses its `GatherPotentialSpawnsCallback`), so skipping the second post is not observable.
* **Expected gain**: ~0.2-0.3% of the server thread (the group-first half of `canSpawnMobAt`). More with structure
  heavy terrain.
* **Risk**: low. If a future mod adds a non-deterministic `PotentialSpawns` listener, the second evaluation of that
  listener would be skipped. Disable this patch in that case.
* **Disable**: `mixin.vanilla.NaturalSpawnerMobsAtMemoMixin=false`

### 3. `compat.chunkactivitytracker.ChunkActivityMapAsyncSaveMixin`: Chunk Activity Tracker saves off the server thread
* **Target**: `toni.chunkactivitytracker.data.ChunkActivityMap`
  - `save()V` `@Inject HEAD cancellable`
  - `load(ResourceKey)` `@Inject HEAD` (drain)
  - `clear()V` `@Inject HEAD` (drain; the mod calls it on server stopped)
* **Problem (spike source)**: the mod keeps one entry for every chunk any player has stood in (it adds one per second
  and never prunes). At the TAIL of every `MinecraftServer.saveEverything` it re-encodes and **GZIP-writes the whole
  map of every loaded dimension synchronously on the server thread**. That happens on the 5-minute autosave, on every
  singleplayer pause (ESC runs `saveEverything`) and on /save-all. It also happens once after the first load of
  each dimension. On a long-played world where you fly a lot, this is megabytes of deflate plus a file write per
  autosave, and it grows with play time. The user's disk is also busy (swap almost full). It matches the reported
  "MSPT max 611 ms within 5 min" pattern. No other mod in the pack reads this data (no class references it).
* **Fix**: the map is still serialised on the calling thread, which gives a consistent snapshot with the mod's own
  `CODEC` and byte-identical content. Compression and the write run on one daemon thread (`ChunkActivitySaveQueue`):
  - writes are ordered and coalesced per file;
  - each write goes to `<dim>.dat.flyaway-tmp` and is then atomically moved over `<dim>.dat`;
  - before the mod loads a file, and when it clears its maps on server stop, the queue is drained (bounded wait of
    60 s), so quitting or re-opening a world never loses or half-reads data.

  If no world path is available or encoding throws, the original synchronous `save()` runs.
* **Expected gain**: the compression and IO part leaves the tick. Only the in-memory encode remains (a few ms for
  ~100k entries). That is tens to hundreds of ms per autosave or pause on an old world.
* **Risk**: low. The data format is unchanged and the log line is kept (with an "off-thread" suffix). A hard crash
  could lose the last save, but the previous file stays intact thanks to temp + atomic move.
* **Disable**: `mixin.compat.ChunkActivityMapAsyncSaveMixin=false`
* **Even better (config/modlist)**: the mod has no dependents in this pack. Removing
  `chunkactivitytracker-neoforge-1.0.1-1.21.1.jar` also removes its per-second player tick and the growing file
  (`<world>/chunk_activity_info/`). Nothing in the current jars references `toni/chunkactivitytracker` except the
  mod itself. It depends on `txnilib`, not the other way round.

---

## Spikes: other sources found (not patched here)

The integrated server's mean MSPT is low (3.5 ms), but its max is 611 ms. Things that run inside a tick and can take
100+ ms:

1. **Autosave / pause save (`saveEverything`)**, every 300 s (ServerCore `autosave-interval-seconds: 300`) and on
   every ESC. It does the following on the server thread:
   - `ChunkMap.saveAllChunks(false)`, which is cheap with c2me's ENHANCED autosave because chunks are already saved
     in idle time;
   - `DimensionDataStorage.save()`, a synchronous NBT+GZIP write for **every dirty SavedData**. Lootr keeps one
     SavedData file per looted container, and there are also OPAC claims, Sable sub-level data, maps, raids, etc.;
   - player data and level.dat;
   - Chunk Activity Tracker (fixed above).

   An async SavedData writer (snapshot NBT on the server thread, write on IO) would be the next step. It was left
   out because the ordering and shutdown semantics across all mods' SavedData are much harder to prove safe than for
   one self-contained file.
2. **Xaero's World Map in singleplayer** (`xaero.map.file.worldsave.WorldDataReader.buildRegion`, xaero area). For
   every region it builds from the world save it runs
   `serverWorld.getServer().submit(() -> serverWorld.getChunkSource().save(false)).join()`. That is a full
   `saveAllChunks(false)` sweep on the server thread per map region. It is cheap while c2me keeps chunks saved, but
   it is a sync point. Seen in the profile at 0.01%. Owner: xaero area.
3. **VSS server LOD generation / Voxy far-entity service / IP chunk tracking** (`vss`, `voxy` and `imm_ptl` areas).
   These are new since the profile and unmeasured on the server thread. VSS has a 1600-line
   `ChunkGenerationService` that generates chunks for far LODs. It adds worldgen load (c2me workers) and main-thread
   chunk promotion. A new Server-thread profile is needed to judge these.
4. **GC pauses**: G1 with `-Xmx12G` and swap almost full. A pause on the server thread shows up as an MSPT spike.
   See `configs.md` for the JVM flags.
5. **Gameplay-triggered**: Sable/Simulated assemble or disassemble (`SimAssemblyHelper`, 0.27% / 0.18% in the
   profile window) and the first load of a dimension (Chunk Activity Tracker load + save, now async).

## Rejected ideas

* **Cache `mobsAt` / `getMobsAt` across positions or ticks**: structure spawn overrides are decided per *piece*
  bounding box (`structureHasPieceAt(pos)`), so the result is position-specific. Only the exact-same-position
  repeat is safe (patch 2).
* **ServerCore `fast-biome-lookups: true`**: it reads the chunk's noise biome without BiomeManager's fuzzing, so
  mobs can spawn a few blocks across biome borders. That is a gameplay change. Our exact memo (patch 1) gives part of
  the gain.
* **Skip the PotentialSpawns event when there are no listeners**: that only helps the second call, which patch 2
  already removes. The first call is ~0.1%.
* **Moog's Structure Lib `getMobsAt` HEAD hook**: when `ReplacementAliases.hasAny()` is true it calls
  `StructureManager.getAllStructuresAt` directly (a ticket-adding load=true lookup, bypassing ServerCore's fix). It
  only matters if a replacement alias is registered, and patching a mixin's merged handler is fragile. It is listed
  here in case a new profile shows `moogs_structures_inheritSpawnOverrides`.
* **Sable physics**: 1.77% of 2.33% is the native Rapier step. The Java side (events, ticket manager, tracking) is
  ~0.3% in total and has no redundant work. Substep count is gameplay (see configs.md).
* **Advancement `TICK` trigger (0.21%)**: it evaluates the not-yet-done advancements with `minecraft:tick` criteria
  for every player tick. Throttling it would change when advancements are granted.
* **Entity tick events** (`fireEntityTickPre/Post` ~1%): the handlers do real per-entity work (Curios, Artifacts,
  Epic Fight patches, Apothic Attributes...). There is no generic win without changing behaviour.
* **Lootr `BlockEntityTicker.onServerTick`**: it is 25 hash lookups per pending loot chunk per tick, which is
  negligible.
* **micimmptl `*SelfTest` / `*Lab` server tick handlers**: they are env-variable gated and return immediately.
* **Polytone / Twilight Forest / Does It Tick hot spots** from the June profile: the mods are no longer installed.

## Config notes (document only)

* `config/servercore/optimizations.yml`:
  - `reduce-sync-loads: true` and `cache-ticking-chunks: true` are on (good). The `tickets` mixins are always on.
  - `fast-biome-lookups` and `cancel-duplicate-fluid-ticks` are not gameplay-neutral. Keep them off.
  - `optimize-command-blocks: true` would be neutral (cached command parse), but it only matters with command
    blocks.
* `config/servercore/config.yml`: activation range, dynamic, breeding cap and villager lobotomy are all off. They
  are gameplay changes, so leave them off. `autosave-interval-seconds: 300` is vanilla.
* `config/c2me.toml`: everything is `default`. For the server tick the relevant default is
  `generalOptimizations.autoSave.mode = ENHANCED`: chunk saves run in idle time between ticks (verified in the
  profile as `c2me$runOneChunkAutoSave` under `waitUntilNextTick`), and vanilla's mid-tick save is disabled. Keep it.
  `PERIODIC` would bring back pre-1.18 all-at-once saves every 6000 ticks, which is a big spike. Worker count and
  priority are covered in `configs.md`.
* `config/chunkactivitytracker-server.toml`: `Store Heightmap Info = false` (good; true would make the file far
  bigger). Consider removing the mod (see patch 3).

## Test plan for the lead

1. Start the client with the full pack and open a world. `latest.log` must show no mixin errors for
   `NaturalSpawnerBiomeMemoMixin`, `NaturalSpawnerMobsAtMemoMixin` or `ChunkActivityMapAsyncSaveMixin`. All use
   require >= 1, so a broken target fails loudly.
2. Spawning still works: stand at night in the open for 2-3 minutes and check that monsters and animals spawn
   normally. Run `/spark profiler start --thread "Server thread" --timeout 120` before and after (or toggle the two
   keys). `NaturalSpawner.getRandomSpawnMobAt` and `canSpawnMobAt` should drop. Under `getBiome` there should be no
   `Level.getChunk` frames, only `SpawnerLookupCache.getNoiseBiome`.
3. Chunk Activity Tracker:
   - press ESC (pause save). The log line should read `Saving data for N chunks for dimension 'overworld' took X
     milliseconds (off-thread, flyaway_perf)`;
   - quit to title and re-open the world. The load line `Loading data for N chunks ...` should show the same N;
   - `<world>/chunk_activity_info/` must have no leftover `*.flyaway-tmp` files.

---

## Lead addition: Sable empty-scene physics step skip (`sable.RapierPipelineEmptyStepMixin` + `sable.RapierBoxHandleTrackMixin`)

* **Problem**: Sable keeps one native Rapier scene per loaded dimension and calls `Rapier3D.step` every physics
  substep (`substepsPerTick` per tick) even when the dimension has **no** sub-level, kinematic contraption or box body
  - only static terrain voxel colliders (`addChunk`). Nothing can move or collide, but the native step still runs its
  broad-phase/query-pipeline maintenance. In the local benchmark world (no ships, 8 dimensions loaded) this was
  **19-23% of the integrated-server thread** (`Rapier3D.step` under `SubLevelPhysicsSystem.tickPipelinePhysics`).
* **Fix**: wrap the `Rapier3D.step` call in `RapierPhysicsPipeline.physicsTick`; skip it when `activeSubLevels`,
  `activeContraptions` and `queuedWakeUps` are empty and no `RapierBoxHandle` is alive for that scene (tracked by
  `RapierBoxHandleTrackMixin` on `create`/`remove`). Only Sable's own rapier module calls the Rapier natives (checked
  by scanning every jar's bytecode), so these are all the dynamic bodies there can be. `Rapier3D.tick`, contraption
  pose updates, wake-ups and collision processing still run. When the first body appears, stepping resumes and
  pending static-collider changes are integrated on that step.
* **Measured**: `sable.debugStats=true` logged `skipped(empty)=9600 stepped=0` per 30 s; `Rapier3D.step` disappeared
  from the server-thread profile; server MSPT in the benchmark went from ~55-60 ms to ~34 ms (noisy environment).
* **Disable**: `sable.skipEmptyPhysicsStep=false` or `mixin.sable.RapierPipelineEmptyStepMixin=false`.
* **Test**: build/assemble an Aeronautics ship in a fresh dimension (first body after a long empty period) - it must
  fall/collide with terrain immediately; ships in other dimensions unaffected.

**Review round 2 fixes / notes (Sable):**
* Ropes (Create Simulated winches; `RapierRopeHandle`) are native bodies that can exist without any sub-level; they
  are now counted like boxes (`sable.RapierRopeHandleTrackMixin`), so a world-anchored rope keeps its dimension stepping.
* Unverifiable from Java: whether Rapier queues static-collider changes (`addChunk`/`removeChunk`/`changeBlock`) until
  the next step. If memory grows over a very long ship-free session, or the first ship assembled after hours shows a
  one-time hitch, set `sable.skipEmptyPhysicsStep=false` and report it.

---

## Round 2 (2026-10-11): per-entity tick listeners on the integrated server

### Data

`local-unpatched-allthreads.sparkprofile` and `local-patched4-allthreads.sparkprofile` (lead's llvmpipe test world:
fresh world, ~16 NoAI mobs at spawn plus natural mobs, a nether portal; the 4-core box is saturated, so the server
runs at 35-60 ms/tick and only **relative shares** mean something). Read with
`java -Dthread="Server thread" ... Tree <profile> 0.3`. For listeners that occur under many entity types the numbers
below are **aggregated over all call sites** (they merge every outermost occurrence of a frame), so they are larger
than the single largest branch that `Tree` shows. All numbers are % of the Server thread in the patched4 profile
(unpatched in brackets where relevant). The java sampler is safepoint-biased: tiny leaf frames at loop back-edges
(`FluidState.isRandomlyTicking` 5.9% inside `tickChunk`, `BoundTickingBlockEntity.getPos` 1.15%,
`lithium$getCachedState` 1.06%) stand for the whole random-tick loop or the first cache-missing touch of each block
entity. They are not removable work.

The mod set changed again since round 1:
* **Accessories is back** (1.1.0-beta.48, jar-in-jar of the Aether jar, mod id `accessories`).
* TACZ, Simply Swords, EF Progressive Difficulty, Caelus and Mowzie's Mobs are present.

`EventHooks.fireEntityTickPre` costs 5.78% and `fireEntityTickPost` 5.82%. The listeners behind them (aggregated):

| listener (event) | % | what it does per entity per tick | decision |
|---|---|---|---|
| Accessories `AccessoriesEventHandler.onLivingEntityTick` (Pre) | **2.89** (1.88) | see patch R2-1 | **R2-1, R2-2** |
| TACZ `LivingEntityMixin.onTickServerSide` (tick RETURN, not the event) | 2.04 (1.55) | 8 shooter sub-ticks + 8 `SyncedDataKey.setValue` (attachment + 2 hash lookups each) for every LivingEntity | rejected, see below |
| Epic Fight `VanillaEntityEventHooks.preTick` (Pre) | 0.89 | entity patch preTick: server animator tick, hurtable state | real work |
| EF Progressive Difficulty `MobEvents.onMobFirstTick` (Post) | **0.76** (0.92) | 257x257 player scan for unscaled mobs | **R2-4** |
| Curios `CuriosEventHandler.tick` (Post) | 0.76 | of which `clearCachedSlotModifiers` **0.34** | **R2-3** |
| Mowzie's `ServerEventHandler.onLivingTick` (Post) | 0.72 | head-slot check, 3 data-attachment ticks, armor/knockback attribute lookups | rejected (real per-entity state) |
| Relics `LeafyMantleItem$CommonEvents.onLivingTick` (Post) | 0.68 | `findEquippedCurios` (Curios capability lookup + slot scan) for every living entity | rejected, see below |
| Caelus `CaelusEvents.livingTick` (Post) | 0.59 | fall-flying attribute lookup, remove + conditional re-add of the elytra modifier | rejected, see below |
| Aether `ArmorAbilityListener.onEntityUpdate` (Post) | 0.43 | 4 armor-set checks (`DeferredHolder.get`, accessory lookup for Phoenix) | rejected (spread, < 0.2% each) |
| Artifacts `ArtifactHooks.livingUpdate` (Post) | 0.38 | dynamic attribute modifiers tick, equipment reduce | rejected (real state) |
| Reliquary `MobCharmItem.onLivingUpdate` (Pre) | 0.30 | target/brain-memory lookups | rejected (small, real) |
| Simply Swords `HivemindSwarmManager.hasActive` (level tick HEAD) | **0.34** (0.18) | iterates every entity of the level | **R2-5** |

### Patches (all in `flyaway_perf.compat.mixins.json`, "mixins", i.e. both sides)

#### R2-1. `compat.accessories.AccessoriesEntityTickIdleMixin` (+ `AccessoriesHolderImplAccessor`, helper `dev.flyaway.perf.compat.AccessoriesIdleTick`)
* **Target**: `io.wispforest.accessories.impl.AccessoriesEventHandler.onLivingEntityTick(LivingEntity)V`, a static
  `@Inject HEAD cancellable`. Accessor and invoker on `AccessoriesHolderImpl` (`loadedFromTag`,
  `containersRequiringUpdates`, `getAllSlotContainers()`). No other mod in the pack mixes into these classes (bytecode
  scan of all jars).
* **Problem**: Artifacts' `data/artifacts/accessories/entity/slots.json` gives 5 accessory slots each to husk,
  skeleton, zombie, drowned, stray, bogged, wither skeleton, piglin, piglin brute, zombified piglin and ghast. Players
  get 12 via `#accessories:defaulted_targets`. For each of these entities, every tick, on both sides, the handler:
  - allocates two HashMaps and two `AccessoryAttributeBuilder`s (HashMap + LinkedHashMultimap + HashSet each);
  - allocates a `SlotReference` and a `slotName + "/" + i` String per slot (plus a debug String inside every
    `getItem`, see R2-2);
  - does four `getHolder()` round trips (an attachment lookup + an `EntitySlotLoader` map lookup each);
  - runs `ImmutableMap.copyOf`, `updateContainers()` and an event post.

  In the normal case (the mob wears nothing) all of this changes nothing. Cost: 2.89% of the server thread (1.88%
  unpatched).
* **Fix**: `AccessoriesIdleTick.tryIdleTick` uses side-effect-free reads to prove that the original would be a no-op.
  All of these must hold:
  - the holder is not `loadedFromTag` and its slot-container count equals the entity's slot count, so `getHolder()`
    is a pure lookup (this is its own condition, negated);
  - every current accessory stack is empty, so there is nothing to tick;
  - on the server, every stack and cosmetic stack passes `ItemStack.matches` against its remembered previous stack.
    This is the original's own change test, so even odd "empty" stacks with count 0 take the original path;
  - no container `hasChanged()`;
  - every container's capability belongs to this entity, so `getContainers()` would not re-bind it;
  - the pending-update map and the invalid-stack list are empty.

  If they hold, only the parts that are still observable run:
  - `AccessoriesCapability.get(entity)` (lazy capability creation);
  - on the server, `ContainersChangeCallback.EVENT.invoker().onChange(entity, capability, ImmutableMap.of())`.
    `ImmutableMap.copyOf` of an empty map returns that same singleton, and the callback has no listener in this pack
    anyway.

  The original's client branch returns right after the slot loop, so on the client only `get()` remains. Any other
  state runs the original method unchanged: an equipped accessory, a change this tick, a resize, invalid stacks or
  freshly loaded NBT.
* **Expected gain**: ~2-2.4% of the server thread with the test world's mob mix, more with many zombies or skeletons.
  The same applies to client-side ticking of those mobs. The remaining cost is `get()` and the slot reads.
* **Risk**: low. The only assumption is that the listed reads have no side effects. `getAccessories()` and
  `getCosmeticAccessories()` call `update()`, which does nothing while the container is not flagged, and the flag is
  checked first.
* **Disable**: `mixin.compat.AccessoriesEntityTickIdleMixin=false`

#### R2-2. `compat.accessories.ExpandedSimpleContainerValidIndexMixin`
* **Target**: `io.wispforest.accessories.impl.ExpandedSimpleContainer.validIndex(I)Z`, `@Inject HEAD cancellable`.
* **Problem**: `validIndex` always builds `"Container: " + name + ", "`, but that string is only used for a
  dev-environment log of an invalid index. Every `getItem`/`setItem`/`removeItem` of an accessory container calls it.
  These containers are read per slot, per entity, per tick by:
  - the handler above, when it is not idle;
  - the client `getItemBySlot` cosmetic hook;
  - Aether, Artifacts, Reliquary and SophisticatedCore accessory scans.
* **Fix**: for `0 <= slot < getContainerSize()`, return `true` at HEAD (the original returns true with no other
  effect). Out-of-range calls run the original. The result is identical on every input.
* **Expected gain**: one String + byte[] per accessory slot read. This is not separately visible in the sampled
  profile (it is inlined into its callers). Small, but free.
* **Disable**: `mixin.compat.ExpandedSimpleContainerValidIndexMixin=false`

#### R2-3. `compat.curios.CurioInventoryClearCachedIdleMixin` (+ `CurioStacksHandlerCachedModsAccessor`)
* **Target**: `top.theillusivec4.curios.common.capability.CurioInventoryCapability.clearCachedSlotModifiers()V`,
  `@Inject HEAD cancellable`, with `@Shadow curioInventory` and an accessor for `CurioStacksHandler.cachedModifiers`.
* **Problem**: Curios' tick handler calls this for every entity with a curios inventory every tick. It allocates a
  `HashMultimap`. Then, for every slot handler, it calls `getCachedModifiers()`, which returns a **new `HashSet` copy**
  of the handler's map, just to call `isEmpty()`. The map is only filled from saved `CachedModifiers` NBT on load.
  Cost: 0.34%.
* **Fix**: if every handler is exactly `CurioStacksHandler` (not a subclass) and its `cachedModifiers` is empty, the
  original's flag stays false, its multimap stays empty and it does nothing. In that case return at HEAD. Otherwise
  run the original.
* **Expected gain**: ~0.3% of the server thread (+ client).
* **Disable**: `mixin.compat.CurioInventoryClearCachedIdleMixin=false`

#### R2-4. `compat.efprogressivediff.MobEventsPlayerScanMixin`
* **Target**: `com.minhhjjj.efprogressivediff.event.MobEvents.getDifficultyAround(Entity,int)D`, `@WrapOperation` of
  `ServerLevel.getEntitiesOfClass(Class, AABB)`.
* **Problem**: despite its name, `onMobFirstTick` is an `EntityTickEvent.Post` listener. It runs for every mob every
  tick until the mob has been scaled (persistent tag `efprogressivediff:difficulty_stored`). Scaling needs a player
  within `groupRadius` (128 in `config/efprogressivediff-common.toml`). So every mob farther than 128 blocks from all
  players re-runs `getEntitiesOfClass(ServerPlayer.class, box.inflate(128))` every tick. That is an entity-section walk
  over ~17x17 chunk columns (c2me filtering iterator, AVL subsets, ClassInstanceMultiMap). Cost: 0.76% (0.92%
  unpatched).
* **Fix**: `getEntitiesOfClass(cls, box)` returns the non-spectator entities of that class that are in tracked
  (accessible) sections and whose bounding box intersects `box`. Every such ServerPlayer is in `ServerLevel.players()`:
  players are added there and removed from there in the same `onTrackingStart/End` callbacks as for the visible entity
  storage.
  - If no player in `level.players()` intersects the box, the original result is empty, so return `List.of()`. The
    caller only tests `isEmpty()` and returns -1.
  - Otherwise the original query runs (same list, same order, so the floating-point sums are unchanged).
* **Expected gain**: ~0.7% of the server thread. It grows with the number of loaded mobs far from players.
* **Disable**: `mixin.compat.MobEventsPlayerScanMixin=false`
* **Config alternative** (gameplay-relevant, not done): a smaller `groupRadius` shrinks the scan box, but changes
  which players count for the scaling.

#### R2-5. `compat.simplyswords.HivemindSwarmHasActiveMixin` (+ `EntityLookupBeeCountMixin`, `LevelEntityGetterAdapterAccessor`, duck `dev.flyaway.perf.compat.SimplySwordsBeeCount`)
* **Targets** (all apply only when `simplyswords` is loaded):
  - `net.sweenus.simplyswords.world.HivemindSwarmManager.hasActive(ServerLevel)Z`, `@Inject HEAD cancellable`;
  - vanilla `net.minecraft.world.level.entity.EntityLookup.add/remove`, `@WrapOperation` of `Int2ObjectMap.put` /
    `Int2ObjectMap.remove` (the `byId` map);
  - an accessor for `LevelEntityGetterAdapter.visibleEntities`. IP has its own accessor on the same class; they do not
    conflict.
* **Problem**: Simply Swords injects `tickFieldManagers` at the HEAD of every `ServerLevel.tick`. Of its ~35
  `hasActive` checks, only the Hivemind one iterates `level.getAllEntities()`: every entity of the dimension, every
  tick, in all dimensions, looking for a swarm bee. Cost: 0.34% here (0.89% for all of tickFieldManagers), linear in
  the number of loaded entities.
* **Fix**: each `EntityLookup` keeps an exact count of the `SimplySwordsBeeEntity` values in its `byId` map. The count
  is adjusted from the actual `put` / `remove` return values, so replaced or missed entries cannot skew it.
  `ServerLevel.getAllEntities()` is `getEntities().getAll()`, which returns the `byId` values of the vanilla
  `LevelEntityGetterAdapter`'s lookup (no mod overrides either; bytecode-checked).
  - If the count is 0, the original loop cannot find a bee, so return false.
  - In all other cases the original scan runs: bees present, a non-vanilla getter, or the counter mixin disabled.

  Cost: one `instanceof` per entity add/remove.
* **Expected gain**: ~0.3% of the server thread, more with more entities.
* **Disable**: `mixin.compat.HivemindSwarmHasActiveMixin=false` (and/or `mixin.compat.EntityLookupBeeCountMixin=false`)

Round 2 total: about **3.5-4% of the server thread** in the test world (~5% of busy tick time). All patches are exact.

### Looked at and rejected (round 2)

* **TACZ `onTickServerSide` (2.04%)**: every LivingEntity runs the shooter sub-ticks and 8 `SyncedDataKey.setValue`
  calls (+ 1 `getValue` in `tickSprint`) per tick. `getServer().execute(...)` runs inline on the server thread (no task
  queue). Each set/get does:
  - an attachment `getData`;
  - `registeredDataKeys.contains` + `dataMap.computeIfAbsent`, hashed with commons `Pair.hashCode`, i.e. two
    ResourceLocation hashes over cached Strings.

  Nothing is computed twice, and the values can change for any mob that picks up a gun. An exact early-out would need
  to re-implement TACZ's shooter state machine. A hash-only micro-fix (an identity cache for the registered-key check)
  would save < 0.3%. Not done.
* **Relics Leafy Mantle `onLivingTick` (0.68%)**: calls `findEquippedCurios` for every living entity.
  - For entities without a curios inventory, the cost is the Curios capability lookup itself (a
    `CuriosApi.getEntitySlots` map lookup). Nothing cheaper can answer "has no curios".
  - For entities with one, the slot scan is the actual question.

  Rejected.
* **Caelus (0.59%)**: removes and conditionally re-adds the elytra modifier on the fall-flying attribute every tick.
  Skipping a remove+add pair would change when the attribute is marked dirty, and therefore when attribute sync packets
  are sent. The rest is one attribute lookup + `canElytraFly`. Rejected.
* **Mowzie's (0.72%), Aether armor (0.43%), Artifacts (0.38%), Reliquary mob charm (0.30%), Epic Fight preTick
  (0.89%)**: each is a set of small, real per-entity checks or state ticks (data attachments, armor-set checks, dynamic
  modifiers, the animator tick). None of them has a large redundant part.
* **Mob AI (`GoalSelector.tick` 4.16% aggregated)**: dominated by vanilla goals: the player/entity searches of
  `TemptGoal`, `NearestAttackableTargetGoal`, `BegGoal` and `LookAtPlayerGoal`, and `RandomStrollGoal` path creation.
  The only modded goal above 0.1% is Mowzie's `AvoidEntityIfNotTamedGoal` (0.13%, a vanilla `AvoidEntityGoal`). Goal
  evaluation is vanilla gameplay timing, and nothing modded dominates.
* **`Entity.move` collision wrappers (9.7% aggregated `Entity.move`)**:
  - The micimmptl `portalAwareCollide` wrapper's own body is a few field reads (`ip_getPortalCollisionHandler`,
    `hasCollisionEntry`). Its 4.0% inclusive cost is what runs underneath: the vanilla + Lithium collide (3.0%) and
    Sable's `SubLevelEntityCollision.collide` (0.93%).
  - Sable's `moveInject` (1.49%) is vanilla `checkSupportingBlock`.
  - Of Sable's 0.93%, **0.68% is micimmptl's `SubLevelGhostCollision.augmentIntersecting`**, mostly
    `SubLevelClientDimensions.loadedLevelsAround(level)`. For EVERY collide of EVERY non-player entity, it builds a new
    `ArrayList` of all server levels, then looks up every level's `SubLevelContainer` and iterates its sub-levels,
    although there are usually none.
  - Not patched here: micimmptl is the user's own mod and is rebuilt often. A `require = 1` mixin into it would crash
    the game after the next micimmptl update.
  - **Recommendation for the micimmptl source** (exact), either of:
    - in `augmentIntersecting`, iterate `serverLevel.getServer().getAllLevels()` directly instead of calling
      `loadedLevelsAround` on the server (no list);
    - keep a static "number of live sub-levels across all containers" counter, updated where sub-levels are added and
      removed, and return `found` immediately when it is 0 and nothing in `found` suppresses.

    Expected ~0.5-0.7% of the server thread.
  - Sable's own `collide` allocates a `LevelAccelerator` + `ObjectOpenHashSet` per call. An empty-scene early-out there
    would have to replicate the end of Sable's collide (tracking/kick logic). Rejected for risk.
* **`Level.tickBlockEntities` (4.93%)**: nothing removable.
  - `BoundTickingBlockEntity.getPos` (1.15%) and Lithium `getCachedState` (1.06%) are the first touch of each block
    entity (cache misses, safepoint bias).
  - Lootr barrels/pots `ILootrBlockEntity.ticker` (0.64%) is Lootr's per-container tick (open state, client sync), real
    work.
* **`ServerChunkCache.tickChunks` (11.85%)**:
  - `tickChunk` (9.13%) is vanilla random ticking (the 5.9% `FluidState.isRandomlyTicking` frame is the whole section
    loop).
  - Precipitation is 1.32%.
  - `NaturalSpawner.createState` (2.0%) iterates all entities and calls `getFullChunk` per mob (c2me's filtering
    iterator). createState could remember the last chunk, but entities of one chunk are not consecutive in the entity
    list (id order), so < 0.4% is possible. Rejected.
* **`LevelTicks` fluid spread** (3.5% unpatched, flowing lava near spawn): real scheduled-tick gameplay.
* **IP**, both real work:
  - `EntitySync.tick` (1.6%) is `ServerEntity.sendChanges` for tracked entities inside IP's packet redirection, i.e.
    vanilla work;
  - `CollisionHelper.updateCollidingPortalForWorld` (0.55%) is mostly `notifyCollidingPortals`, an entity box query
    around each portal (0.30%).
* **`waitUntilNextTick` (21%)**: idle time, including c2me's idle-time autosave. Not tick work.

### For the lead to test (round 2)

1. With `-Dflyaway.logMixins=true`, the nine new mixins apply:
   - `AccessoriesEntityTickIdleMixin -> io.wispforest.accessories.impl.AccessoriesEventHandler`
   - `AccessoriesHolderImplAccessor -> ...AccessoriesHolderImpl`
   - `ExpandedSimpleContainerValidIndexMixin -> ...ExpandedSimpleContainer`
   - `CurioInventoryClearCachedIdleMixin -> top.theillusivec4.curios.common.capability.CurioInventoryCapability`
   - `CurioStacksHandlerCachedModsAccessor -> top.theillusivec4.curios.common.inventory.CurioStacksHandler`
   - `MobEventsPlayerScanMixin -> com.minhhjjj.efprogressivediff.event.MobEvents`
   - `EntityLookupBeeCountMixin -> net.minecraft.world.level.entity.EntityLookup`
   - `HivemindSwarmHasActiveMixin -> net.sweenus.simplyswords.world.HivemindSwarmManager`
   - `LevelEntityGetterAdapterAccessor -> net.minecraft.world.level.entity.LevelEntityGetterAdapter`
2. Accessories:
   - Equip and unequip an Aether accessory (gloves, a ring, a cape) in the accessories screen. The attribute/ability
     must apply and be removed as before, and it must be visible in F5 and to other players.
   - Re-log with an accessory equipped (the loadedFromTag path).
   - If an Artifacts-equipped mob spawns (zombie/skeleton wearing an artifact), it must still show and drop the item.
3. Curios: equip a curio that gives attributes (e.g. a Relics item) and re-log (the cached-modifier path). Check that
   the attribute is applied exactly once (`/attribute`).
4. EF Progressive Difficulty:
   - Mobs that spawn within 128 blocks still get `efprogressivediff:difficulty_stored` (check with `/data get entity
     <mob>` or the mod's difficulty command).
   - Fly more than 128 blocks away and come back: far mobs get scaled once a player comes in range.
5. Simply Swords: use a Hivemind-ability weapon. The swarm bees must still be ticked (follow and attack targets,
   despawn when the owner leaves). In a Server-thread spark profile, `HivemindSwarmManager.hasActive` should disappear
   when no bees exist.
6. Profile again:
   - `AccessoriesEventHandler.onLivingEntityTick` should drop from ~2.9% to well under 1%;
   - `MobEvents.getDifficultyAround` and `clearCachedSlotModifiers` should be near 0.
