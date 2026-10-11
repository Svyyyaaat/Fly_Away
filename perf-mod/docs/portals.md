# Portals area: Immersive Portals, make-it-compatible (micimmptl), Neo Voxy, VSS, Sable

Mixin areas: `imm_ptl` (needs `immersive_portals_core`), `vss` (needs `vss`), `vanilla.portals`. There are no
`voxy` or `sable` mixins; the ideas for them were evaluated and rejected (see below).
Numbers are % of the render thread in the user's profile (`user-profile-s4gidwMiAz.sparkprofile`, 92.6 s,
singleplayer).

The user's own `make-it-compatible-immersive-portals-1.1.0-neovoxy-r21.jar` (micimmptl) already patches several of
the same spots. All patches below were written to sit on top of its mixins. They do not replace them:

| micimmptl mixin | target | how ours coexists |
|---|---|---|
| `ImmPtlClientChunkMapFastPathMixin` (4-slot MRU, `@Inject HEAD`) and `ImmPtlClientChunkMapPlotMixin` (plot chunks with \|x\|,\|z\| > 62500) | `ImmPtlClientChunkMap.getChunk` | Our `@WrapMethod` wraps both. It returns early only for a chunk that is present in `chunkMapForMainThread` at normal coordinates on the main thread, which is exactly what both of them return in that case. Every other case calls the original chain. |
| `PortalRenderInfoNonBlockingQueryMixin` (`@WrapOperation` fetchQueryResult ordinal 0) | `PortalRenderInfo.renderAndDecideVisibility` | We wrap a different call site (ordinal 1) and only when an opt-in tunable is set. |
| none on `SubLevelStraddle` | micimmptl's own class | `@Pseudo` `@WrapMethod`. It is a no-op when micimmptl is absent. |

---

## Patches

### 1. `imm_ptl.ImmPtlClientChunkMapFastGetMixin`: client chunk lookup fast path
* **Target**: `qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap.getChunk(IILChunkStatus;Z)LevelChunk`
  (`@WrapMethod`, so it sits outside IP's body and outside both micimmptl injectors).
* **Problem**: 1.74% total, and 1.72% of that is "self" at line 0 of the injected method. That self time is the
  cost of the call itself. Spark's Java sampler is safepoint-biased, so samples land on the return poll. Every
  `Level.getChunk` on a client level goes through here: `LevelReader.getBiome` from `ClientLevel.animateTick`
  (0.82%), `getBlockState` (0.44%), fog colour biome sampling, entity light checks and so on. Each call pays for
  stock IP's capturing lambda plus the `readChunkMap` indirection. It also pays for 2 micimmptl cancellable
  `@Inject HEAD`s, each of which allocates a `CallbackInfoReturnable`. On top of that come the plot-range checks and
  the MRU array shuffling.
* **Fix**: on the client main thread, for |x|,|z| <= 62500, read `chunkMapForMainThread.get(ChunkPos.asLong(x,z))`
  and return the chunk if it is non-null. Misses (null / `emptyChunk`), other threads and plot coordinates call the
  original chain unchanged. The result is identical: micimmptl's MRU is cleared on every `modifyChunkMap`, so it
  always agrees with the map.
* **Expected gain**: most of the ~1.7% (one fastutil probe instead of 2 handlers, 2 allocations and a lambda).
* **Risk**: low. The `mainThread` field and the 62500 bound are taken from IP and micimmptl themselves.
* **Disable**: `mixin.imm_ptl.ImmPtlClientChunkMapFastGetMixin=false`

### 2. `imm_ptl.SubLevelStraddlePortalCacheMixin`, `imm_ptl.PortalGeometryInvalidateMixin` and `vanilla.portals.GameRendererFrameScopeMixin`: per-frame portal query memo
* **Target**: `dev.micimmptl.sable.render.SubLevelStraddle.portalsIn(Level, AABB)` (private static,
  `@Pseudo @WrapMethod`).
* **Problem**: `SubLevelStraddle.portalsIn` costs 0.60%. It is the biggest contributor to the
  `EntitySectionStorage.getEntities` total (1.35%) and to `SubLevelInclusiveLevelEntityGetter.get` (1.62%).
  micimmptl's Sable-hull portal clipping (`clipHullAtPortal`, `mic$equationFor`, `drawHullsComingThroughThisPortal`,
  `drawHullsStillHangingOutOfPortals`) asks "which portals are near this hull" through
  `findStraddledPortal -> bestStraddledIn -> candidates -> portalsIn`. It does this for every hull, every chunk
  layer and every pass (main, shadow and each portal view). Each ask is a `McHelper.findEntitiesByBox(Portal.class,
  box inflated by 32)` entity-section scan through Sable's sub-level-inclusive entity getter, which queries the
  sections twice. The answer does not change during a frame.
* **Fix**:
  * `PortalFrameScope` is an "inside `GameRenderer.renderLevel`" window. It is depth-counted, because IP re-enters
    `renderLevel` for each portal view, and it is hard-reset at `GameRenderer.render` HEAD and RETURN. Inside this
    window no tick or packet handling runs, so entities are frozen.
  * While the window is open, on the render thread, `portalsIn` results are memoized per (level identity, exact
    box) in `FramePortalQueryCache`.
  * The cache is cleared when the window opens or closes.
  * Safety net: `Portal.updateCache()` HEAD clears the cache whenever a portal's geometry changes inside the
    window. Every portal setter and `ip_onEntityPositionUpdated` go through `updateCache()`. This matters because
    micimmptl re-poses portals mounted on Sable vehicles at `RenderLevelStageEvent.AFTER_SKY`, which runs after the
    Iris shadow pass has already queried.
  * All callers only iterate the returned list (checked with javap: `candidates` and `isCrossedFace` iterate,
    `transitablePortalsIn` copies).
  * Outside the window (ticks, collision code) the original method runs.
* **Expected gain**: about 0.5% of the render thread, plus GC pressure from the ArrayLists and lambdas.
* **Risk**: low to medium. The risk is a portal that moves inside a frame without going through `updateCache`.
  Stock IP and micimmptl always go through it. The cache holds at most 512 entries and never holds a Level across
  frames.
* **Disable**: `mixin.imm_ptl.SubLevelStraddlePortalCacheMixin=false`. The scope and invalidation mixins are
  harmless on their own.

### 3. `vss.LodRequestManagerStrictKeysMixin`: fix the colliding Long keys in the VSS LOD request manager
* **Target**: `dev.xantha.vss.networking.client.LodRequestManager.<init>(String, ClientRequestTracker, Runnable)`,
  `@Inject RETURN`. This is the only constructor that assigns the field (the others delegate to it).
* **Problem**: `LodRequestManager.tick` costs 1.09%. Under it, `ConcurrentHashMap$TreeNode.findTreeNode` is 0.60%
  self (via `shouldRequestColumn`, `strictColumnReady` and `restoreKnownColumn`). `strictSections` is a
  `ConcurrentHashMap<Long,int[]>` keyed by the packed column `cx<<32 | cz`. `Long.hashCode` of such a key is
  `cx ^ cz`, so every column on the same "xor diagonal" collides. An N x N LOD window has only about N distinct
  hashes, so CHM turns its bins into red-black TreeBins and every `containsKey` becomes a tree walk with
  `Long.compareTo`.
* **Fix**: replace the still-empty map with `dev.flyaway.perf.vss.MixedLongKeyMap`. It multiplies every key by an
  odd 64-bit constant before it reaches CHM. That is a bijection, so the semantics are unchanged and bins hold about
  one entry. Only point operations are remapped. The field is only used with get, containsKey, put, putIfAbsent,
  remove and clear, and never iterated (checked with javap). A randomized differential test against a plain CHM
  (200k mixed operations) matched. A random-lookup micro-benchmark was 1.5 to 2.5x faster.
* **Expected gain**: about 0.4 to 0.5% of the render thread. The same applies on any other thread that touches the
  map.
* **Risk**: low. If a future VSS version iterates the map, the keys it sees would be mixed. Re-check this when VSS
  updates (the mixin fails loudly if the constructor changes).
* **Disable**: `mixin.vss.LodRequestManagerStrictKeysMixin=false`

### 4. `imm_ptl.PortalRenderInfoNoSyncWhenHiddenMixin` (opt-in, OFF by default)
* **Target**: `PortalRenderInfo.renderAndDecideVisibility`, `@WrapOperation` on `GlQueryObject.fetchQueryResult()`
  ordinal 1 (IP's `fetch_this_frame` branch).
* **Problem**: 1.27% is a synchronous `glGetQueryObjectiv(GL_QUERY_RESULT)`. micimmptl already made the
  previous-frame read (ordinal 0) non-blocking. The remaining stall is IP's own design: when last frame said
  "hidden" and `noPredict` holds, IP reads the query it has just issued, which forces the CPU to wait until the GPU
  has finished the frame so far. `noPredict` holds for the first 4 queries of every frame, because
  `queryStallCounter` is reset each frame, and it holds forever for a portal after 5 mispredictions. In practice
  this happens every frame for every portal that is in the frustum but occluded.
* **Fix (tunable)**: with `imm_ptl.occlusion.noSyncWhenHidden=true`, that branch answers "still hidden" instead of
  waiting. The query was still issued, so the next frame uses the fresh result.
* **ON by default (lead decision)**: the user's 2026-10-03 profile spent **15.2%** of the render thread blocked in
  exactly this read (`renderAndDecideVisibility:237`), the 2026-10-11 one 1.27%. The cost of enabling it is that a
  portal coming out from behind an occluder is drawn one frame late, for one frame. Review fix: the skip only happens when last frame's
  query result was actually available (non-blocking `GL_QUERY_RESULT_AVAILABLE` check via `GlQueryObjectIdAccessor`).
  micimmptl serves a cached answer while last frame's result is pending; that cached "hidden" can be many frames old
  when the GPU runs behind, so in that case IP's original read is kept and a portal can never stay hidden for longer
  than one frame. The brief's alternative of "render while the result is pending" was
  rejected: it would render the whole world again (about 10% of the frame) for every occluded portal, which is much
  more than the 1.27% stall it would save. Ordinal 2 (first frame with no previous query) is not touched.
* **Expected gain**: 1.3% to 15% depending on how many occluded portals are on screen, plus the CPU/GPU overlap it restores.
* **Risk when enabled**: one-frame pop-in of portals that become visible. No effect when disabled: the original
  call is made.
* **Disable**: set `imm_ptl.occlusion.noSyncWhenHidden=false` (stock behaviour). To remove the
  wrapper entirely, set `mixin.imm_ptl.PortalRenderInfoNoSyncWhenHiddenMixin=false`.

---

## Evaluated and rejected

* **Skipping or reducing work inside portal passes**
  (`IrisCompatibilityPortalRenderer.renderPortalContent` 10.37%: shadow pass 1.96%, terrain, entities, Iris begin
  and finalize, aaa_particles, block entities). Each pass renders the world from a different camera. The Iris
  shadow map, custom uniforms, Sodium visibility lists and Voxy viewports are all camera-dependent, and micimmptl
  already gives each pass its own state (and skips the sun shadow pass where there is no sun). Nothing in there is
  provably redundant. Skipping any of it would change what the portal shows.
  * `aaa_particles` runs `EffekseerManager.update` per pass. Its delta comes from a per-call delta tracker, so the
    second call is not a double simulation.
* **`ClientWorldLoader.tick`** (1.06%): ticks the entities of other loaded client worlds (Endermen, Striders) that
  are visible through portals. Throttling it would make entities behind portals stutter, which is a visible change.
* **`PacketRedirectionClient`** (0.44%): the time is real packet handling (entity moves, chunk data) for the
  redirected world. There is no overhead to remove.
* **`withSwitchedWorld`**: the time is entirely in the task it wraps.
* **Voxy** (`renderOpaque` 3.5%, `runPipeline` 2.56%): the time is spread over many GL calls of 0.05 to 0.2% each.
  * `DownloadStream`/`UploadStream` already create a fence only when there was traffic.
  * `HiZBuffer.buildMipChain` (0.54%) could use one FBO per mip level instead of re-attaching each level, which
    would save about 0.1%. That needs an `@Overwrite` of GL code and was not worth the risk.
  * `glFenceSync` self (0.95%) is mostly Sodium's `postRender` and Flywheel/Colorwheel staging, which are other
    areas.
  * The `VoxySableDepthShim` depth copies (about 0.8%) are GPU state work whose output is used.
* **Sable `ActiveSableCompanion.getAllIntersecting`** (0.21% self, mostly from `Particle.move` via Sable's
  `moveWithSubLevels`): the self time is `instanceof SubLevelContainerHolder` checks on a `ClientLevel` that has
  hundreds of mixin interfaces. That is the JDK 21 secondary-supers cache problem (JDK-8180450, fixed in JDK 23+).
  Caching the container per level would save under 0.2%, and the container lifecycle is not ours to assume.
* **Sable `drawRenderSources` and `sable$renderEntity`** (5.42% / 8.71% inclusive): these are pure wrappers. The
  time is in the sub-level and entity rendering they call, plus micimmptl's clip code. The `portalsIn` part of the
  clip code is handled by patch 2.
* **`IPMcHelper.getNearbyPortals`** (0.40%, several callers per frame): it is spread across 4 callers at under
  0.1% each, and some of them run at render HEAD before the frame scope opens. Low value.
* **`ImmPtlClientChunkMap` with a thread-local MRU**: the plain fastutil probe in patch 1 is already the cheapest
  correct lookup. An MRU in front of it needs invalidation hooks and does not beat one hash probe.

## Config recommendations (pack `config/`)
* Immersive Portals: if one-frame portal pop-in is acceptable, set
  `imm_ptl.occlusion.noSyncWhenHidden=true` in `config/flyaway_perf.properties`. IP's own
  `visibilityPrediction` (`IPGlobal.offsetOcclusionQuery`) must stay true, which is the default; with it false,
  every portal test is a synchronous query.
* No other changes. The relevant IP options (chunk loading radii and so on) are already managed by micimmptl.

## For the lead to test
* The log should show all 4 `imm_ptl` mixins, `vss.LodRequestManagerStrictKeysMixin` and
  `vanilla.portals.GameRendererFrameScopeMixin` applying with no "Critical injection failure".
  `SubLevelStraddlePortalCacheMixin` only applies once micimmptl's `SubLevelStraddle` class loads (`@Pseudo`).
* Look through a nether portal: the portal view should look unchanged. Ride a Sable/Aeronautics vehicle through a
  portal: hull clipping at the portal plane should be unchanged, including for portals mounted on vehicles.
* VSS LOD loading should still fill in around the player (strict LOD visibility mode).
* Optional: set `imm_ptl.occlusion.noSyncWhenHidden=true`, walk behind a wall next to a portal and step out. The
  portal should appear at most one frame late.
