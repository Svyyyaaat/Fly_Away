# Xaero maps / HUD / client tick: patches

Owner area: Xaero's World Map 1.46.0, Xaero's Minimap 26.5.0, XaeroPlus 2.36.3, the HUD/GUI layer, and generic
client-tick overhead that isn't entity rendering.
Mixin packages: `mixin.xaero.xaeroworldmap`, `mixin.compat.curios`, `mixin.compat.irons_spellbooks`,
`mixin.vanilla.clienttick`.
Profile: `user-profile-s4gidwMiAz.sparkprofile`, render thread only, 92.6 s. All percentages below are shares of
render-thread time.

Every patch returns exactly what the unpatched code would return, apart from patch 1, which reports a free-VRAM
number up to 1 s old to Xaero's region limiter. None of the patches change anything visible.

## Patches

### 1. `xaero.xaeroworldmap.MapLimiterVramThrottleMixin`: GPU memory query at most once per second
- **Target:** `xaero.map.MapLimiter.updateAvailableVRAM()` (HEAD, cancellable). Client mixin.
- **Problem:** `MapProcessor.onRenderProcess` runs on every `RenderFrameEvent.Post`. At its end it calls
  `updateAvailableVRAM()`, which on NVIDIA/AMD calls `glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX
  / GL_TEXTURE_FREE_MEMORY_ATI)`. That is a driver query, and with threaded optimisation it forces a sync with the
  driver thread. Profile: 0.17%, all of it in `nglGetIntegerv`.
- **Fix:** The value is only read by `MapLimiter.applyLimit` on the map processor thread, which unloads regions when
  free VRAM drops below 200 MB. After the first call (which detects the driver type), the query runs at most once
  every `xaero.vramQueryIntervalMs` milliseconds (default 1000). In between, the previous value is kept.
- **Expected gain:** ~0.17%, plus one fewer CPU/GPU sync point per frame.
- **Risk:** Very low. Region unloading reacts up to 1 s later when VRAM is nearly full.
- **Tunable:** `xaero.vramQueryIntervalMs=1000`. Set it to 0 for the original per-frame behaviour.
- **Disable:** `mixin.xaero.MapLimiterVramThrottleMixin=false`

### 2. `xaero.xaeroworldmap.DimensionHighlighterHandlerVersionMixin` + `MapRegionHighlightHashMixin`: versioned memo of the region highlight hash
- **Targets:**
  - `xaero.map.highlight.DimensionHighlighterHandler`: `clearCachedHash(II)V` and `clearCachedHashes()V` (RETURN).
    This mixin adds an `AtomicInteger` version and implements `dev.flyaway.perf.xaero.HighlightHashVersioned`.
  - `xaero.map.region.MapRegion.processWhenLoadedChunksExist(I)V`: `@WrapOperation` on the
    `DimensionHighlighterHandler.getRegionHash(II)I` call.
  - Both are client mixins.
- **Problem:** Every frame, `onRenderProcess` calls `processWhenLoadedChunksExist` for each to-process region that has
  loaded textures. That method calls `getRegionHash`, which takes a lock (`synchronized`), does a
  `Long2ObjectOpenHashMap.get` and unboxes the result, only to compare the hash with one the region already stores.
  Profile: `Long2ObjectOpenHashMap.get` 0.29% self, all from this one call site.
- **Fix:** Xaero's `hashCodeCache` only loses entries in `clearCachedHash` / `clearCachedHashes`. An entry that is
  present never changes, and a missing entry is recomputed deterministically. So:
  - The handler bumps its version after every removal.
  - Each region remembers the version and hash from its last real lookup, packed into one volatile long.
  - While the version is unchanged, the region returns the remembered hash without touching the map.
  - The version is read before the real lookup, so a concurrent clear can only cause one extra lookup, never a stale
    result.
  - If only the handler mixin is disabled, the `instanceof` check falls back to the original call.
- **Expected gain:** ~0.25–0.3%, including the lock it no longer takes.
- **Risk:** Low. The result is identical. The only assumption is that nothing else writes `hashCodeCache`; I checked
  every write in the decompiled 1.46.0 source, and XaeroPlus has no mixin on these classes.
- **Disable:** `mixin.xaero.MapRegionHighlightHashMixin=false`. Disabling the version mixin also disables the memo.

### 3. `compat.curios.CurioInventoryItemKeyMixin`: memoise Curios' per-item cache key
- **Target:** `top.theillusivec4.curios.common.capability.CurioInventoryCapability`, methods
  `findFirstCurio(Item)` and `findCurios(Item)`, `require = 2`. Two `@WrapOperation`s:
  - on `Item.getDefaultInstance()`: returns `ItemStack.EMPTY` as a placeholder;
  - on `Curios.itemCacheKey(ItemStack)`: returns the memoised key.
  - Common mixin, so the integrated and dedicated server threads benefit too.
- **Problem:** Both methods build `Curios.itemCacheKey(item.getDefaultInstance())` on every call. That means a new
  default `ItemStack` (component map plus the owo/accessories hooks) and a String made from `toString()` of every
  default component through a stream `reduce`. Curios uses the string only as the key of its own per-game-tick result
  cache.
  - The hot caller here is Iron's Spellbooks' `Entity.isInvisibleTo` hook →
    `CurioBaseItem.isEquippedBy(player)` → `findFirstCurio(INVISIBILITY_RING)`. Xaero's radar runs it for every
    scanned entity each tick (`RadarStateUpdater.isInvisibleTo` 0.64%), and name tags and the glider layer run it
    each frame.
  - Profile: `findFirstCurio` 0.95%, of which `itemCacheKey` 0.48% and `getDefaultInstance` 0.13%.
- **Fix:** `dev.flyaway.perf.compat.CuriosItemKeys` keeps a `ConcurrentHashMap<Item, String>` of the exact same key.
  An item's default instance is fixed once registries are frozen: default components come from registration or
  `ModifyDefaultComponentsEvent`, never from datapacks. The key strings are identical, so Curios' cache and its
  results are unchanged.
- **Expected gain:** ~0.55%, with a similar saving on the server thread wherever mods call `findFirstCurio(Item)`.
- **Risk:** Low. It would only matter if some item's `getDefaultInstance()` were non-deterministic, and then
  Curios' own cache key would already be unstable.
- **Disable:** `mixin.compat.CurioInventoryItemKeyMixin=false`

### 4. `compat.irons_spellbooks.CurioBaseItemEquippedMemoMixin`: per-tick memo of "ring equipped" on the render thread
- **Target:** `io.redspace.ironsspellbooks.item.curios.CurioBaseItem.isEquippedBy(LivingEntity)Z`. Two injects,
  HEAD (cancellable) and RETURN. Listed under `client` because it uses `RenderSystem`.
- **Problem:** After patch 3, each `isInvisibleTo(localPlayer)` call still does a capability lookup, which allocates
  a new `CurioInventoryCapability` wrapper, plus a Guava cache probe and some Optional handling. It is always the same
  (local player, invisibility ring) pair, for dozens of entities per tick and per frame. That remaining part is about
  0.3% of the 0.95%.
- **Fix:** Curios caches `findFirstCurio` per (entity, item, level game time) and never invalidates it within a tick.
  So remembering the last (entity, game time) → result for each `CurioBaseItem` instance returns exactly what Curios
  would. The entity is held through a `WeakReference`. The memo is used only on the render thread; the integrated
  server thread takes the original path.
- **Expected gain:** ~0.3%.
- **Risk:** Very low. Subclasses that override `isEquippedBy` without calling `super` are simply not affected.
- **Disable:** `mixin.compat.CurioBaseItemEquippedMemoMixin=false`

### 5. `vanilla.clienttick.ClientLevelAnimateTickBiomeMixin`: chunk memo for `animateTick` biome lookups
- **Target:** `net.minecraft.client.multiplayer.ClientLevel`:
  - `animateTick(III)V` HEAD: resets the memo;
  - `doAnimateTick(...)`: `@WrapOperation` on `ClientLevel.getBiome(BlockPos)`.
  - Client mixin, listed in `flyaway_perf.vanilla.mixins.json`.
- **Problem:** `animateTick` makes 2 × 667 random `doAnimateTick` calls per client tick, and Sable adds more for
  sub-levels at TAIL. For each position that isn't a full collision block, vanilla calls `getBiome(pos)` to look up
  the ambient particle. That goes `BiomeManager.getBiome` → `LevelReader.getNoiseBiome` →
  `getChunk(cx, cz, BIOMES, false)` → `ImmPtlClientChunkMap.getChunk`, which takes a lock and does a map lookup.
  Profile: `getBiome` 1.01%, of which 0.80% is the chunk lookup.
- **Fix:** `dev.flyaway.perf.vanilla.clienttick.AnimateTickBiomeCache` builds a `BiomeManager` from the level's own
  manager with `withDifferentSource`, so it has the same zoom seed and works with c2me's native `getBiome`
  overwrite. Its noise source copies `LevelReader.getNoiseBiome` exactly, including the
  `getUncachedNoiseBiome` path for missing chunks, but remembers each chunk (or its absence) per chunk position until
  the next `animateTick`. As an extra guard, it also resets when the game time changes. Chunks are only loaded or
  unloaded by packet tasks on the render thread, never during `animateTick`, so it returns the same chunk objects and
  biome holders vanilla would.
- **Expected gain:** ~0.7%.
- **Risk:** Low. Immersive Portals' per-dimension `ClientLevel`s each get their own memo. Sable's sub-level calls use
  plot coordinates and simply get their own entries.
- **Disable:** `mixin.vanilla.ClientLevelAnimateTickBiomeMixin=false`

**Total expected:** about 2.0–2.3% of render-thread time, plus server-thread savings from patch 3.

## Evaluated and rejected (or left to config)

- **World-map per-pixel colouring** (`LeafRegionTexture.preUpload` 1.38%: `MapPixel.getPixelColours` 0.60% self,
  `BlockTintProvider` → `BlockColors` → `BiomeColorCalculator` 0.70%):
  - Uploads are already time-budgeted inside `onRenderProcess`. Each frame Xaero starts a new texture only while
    `System.nanoTime() < uploadUntil`, which is about ¼ of the measured free frame time and at least 0.75 ms. While
    there is a backlog, a faster pixel path just fills the map sooner and doesn't lower frame time.
  - Caching block tints is unsafe in general, because modded `BlockColor`s can depend on position or randomness.
  - Not patched. The config-side lever is `biome_blending` (see below).
- **World-map writer** (`MapWriter.writeChunk` 1.0%, `loadPixel` → `getBrightness` 0.28%, `applyToEachLoadedLayer`
  region lookups 0.27%):
  - The writer is rate-limited to about 1.5 chunks/ms of elapsed time, capped by a time limit, and only rescans
    chunks that changed (it tracks a chunk "clean" flag).
  - Replacing `getBrightness` with direct `DataLayer` access would bypass the light engine's sky-light column logic
    and any light-engine mixins, so the risk is not worth about 0.2%.
- **Lowering or spreading Xaero's per-frame budget:** this would change how fast the map fills in, which is visible.
  Not done.
- **Minimap FBO re-render** (`renderChunksToFBO` 0.96%): XaeroPlus "Minimap FPS Limiter" is already on at 20 FPS
  (`config/xaeroplus.txt`), so the FBO is not redrawn every frame. Lowering the limit further is visible: the minimap
  rotation gets choppier.
- **XaeroPlus ellipse draw feature:** the Caffeine `refreshIfNeeded` path costs 0.11%. Too small to justify a
  patch.
- **Jade `WailaTickHandler.tickClient`** (0.65%): spread over capability checks in `shouldRequestData` (0.19%),
  `gatherComponents` (0.17%) and the ray trace (0.09%). Throttling it would delay the tooltip, which is visible.
  Config levers are below.
- **Animated texture uploads** (`TextureManager.tick` 0.94%, `glTexSubImage2D` 0.71%): Sodium's
  `animate_only_visible_textures` is already `true` in `sodium-options.json`. What remains is visible sprites,
  interpolated (`interpolate: true`) sprites and non-terrain atlases. Skipping any of these would be visible or
  wrong.
- **Particle tick** (0.70%): most of it is particle_core's light updates and Sable's `moveWithSubLevels`, which belong
  to other areas. Nothing safe to do here.
- **EventBus overhead** (`EventBus.post` 0.89% self, `SubscribeEventListener.invoke` 0.42% self): spread over many
  post sites:
  - per-entity render events fired by ModernFix's `fireCheckingPoseStack` (5.15% inclusive, entity-render area);
  - `RenderFrameEvent.Post` (3.95% inclusive, mostly Xaero);
  - `ClientTickEvent.Post` (3.61%, mostly VSS and Jade);
  - `RenderLevelStageEvent`, about 3.8% across vanilla, Sodium's per-chunk-layer dispatch and Immersive Portals'
    extra passes;
  - entity tick Pre/Post (0.8%).

  The listeners are third-party code. Generic bus changes would affect every mod. I made the expensive listeners in
  my area cheaper (patches 1–5) and left the bus itself alone.
- **`glGetError` loops in Xaero:** negligible in the profile (about 0.00%). They also drain other mods' GL errors on
  purpose, so leave them.
- **`LocalPlayer.tick` / entity tick** (3.89%): very spread out (Epic Fight patches, Shoulder Surfing input events,
  Accessories, LambDynLights). There is no single hotspot in my area; the entity-tick listeners belong to the entity
  and Epic Fight owners.

## Config-only recommendations (for the config owner; not applied here)

| File / key | Current | Suggestion | Effect |
|---|---|---|---|
| `config/xaero/world-map/profiles/default.cfg` `biome_blending` | true | optional `false` | Each blended pixel does 5 biome lookups instead of 1 (`BiomeColorCalculator` 0.55% self). Visible: sharper biome borders on the world map. User's choice. |
| `config/xaero/minimap/profiles/default.cfg` `minimap_biome_blending` | true | optional `false` | Same trade-off for the minimap. |
| `config/xaero/world-map/profiles/default.cfg` `map_writing_distance` | -1 (render distance) | leave, or 8–12 if render distance is large | Writer throughput is rate-limited, so this mainly shortens the refresh cycle. A smaller area means fewer region loads and less writer bookkeeping. |
| `config/xaero/world-map/client.cfg` `max_loaded_regions` | 300 | leave | Fine with 32 GB RAM and the VRAM limiter. |
| `config/xaeroplus.txt` `[XP] Minimap FPS Limiter` / `Limit` | true / 20 | keep | Already the biggest minimap saving. Don't let a config reset turn it off. |
| `config/xaero/minimap/profiles/default.cfg` `radar_hide_invisible_entities` | true | keep (gameplay) | This is why the radar calls `isInvisibleTo` for every entity. Patches 3 and 4 make it cheap. |
| `config/xaero/minimap/.../entity_radar_categories` | default | optional: turn off radar for item/XP-orb/"other entities" categories | Fewer entities scanned per tick in `RadarStateUpdater` (0.89%) and drawn on the minimap. Visible: fewer dots. |
| `config/jade/plugins.json` `"minecraft": { "item_storage", "fluid_storage", "energy_storage" }` | on | optional off | Removes per-tick capability lookups in `shouldRequestData` (~0.15%). Visible: no storage contents in Jade tooltips. |
| `config/sodium-options.json` `animate_only_visible_textures` | true | keep | Already enabled. |

## What the lead should test

1. Mixins apply with no errors in the log:
   - `MapLimiterVramThrottleMixin`, `DimensionHighlighterHandlerVersionMixin` and `MapRegionHighlightHashMixin`
     (needs xaeroworldmap);
   - `CurioInventoryItemKeyMixin` (`require = 2`, on both `findFirstCurio(Item)` and `findCurios(Item)`);
   - `CurioBaseItemEquippedMemoMixin`;
   - `ClientLevelAnimateTickBiomeMixin`.
2. World map and minimap:
   - map tiles still appear and update when blocks change;
   - XaeroPlus/OPAC highlights (claims, WorldTools highlights) still redraw when they change, which checks the
     version bump;
   - opening the full-screen map still works.
3. Iron's Spellbooks invisibility ring:
   - invisible mobs appear on the radar or show name tags only while the ring is equipped;
   - equipping or unequipping takes effect within one tick, as before.
4. Ambient biome particles still spawn in the right biomes: Nether (crimson/warped spores, basalt ash, soul sand
   valley) and modded biomes with particles. Also check them near Sable ships, where sub-level animate ticks run.
5. Profile: `nglGetIntegerv` under `MapLimiter` should disappear. `Curios.itemCacheKey` should disappear. Under
   `doAnimateTick`, `getBiome` should drop from about 1.0% to about 0.25%. `getRegionHash` should be close to zero.
