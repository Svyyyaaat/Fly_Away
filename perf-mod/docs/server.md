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
