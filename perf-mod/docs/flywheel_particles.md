# Flywheel / Colorwheel / Create rendering + particles

Scope: Flywheel 1.0.6 (jar-in-jar in Create 6.0.10, Vanillin 1.1.3), Colorwheel 1.3.0 (+ colorwheel_patcher),
Create 6.0.10 client rendering and Ponder/Catnip, Create addons, vanilla particles, particle_core 0.3.3,
aaa_particles 2.2.3 (Effekseer), fallingleaves, block entity rendering.

All numbers are % of the render thread in the user's profile (`user-profile-s4gidwMiAz.sparkprofile`, 92.6 s,
4 ms sampling). Spark samples on Windows are safepoint-biased: inclusive totals are reliable, the split
between a method and its inlined callees is not.

All patches can be switched off in `config/flyaway_perf.properties`, either one at a time
(`mixin.<area>.<MixinClassName>=false`) or by area (`area.compat=false`, `area.vanilla=false`).

## Patches

### 1. aaa_particles: skip Effekseer draws when there is nothing to draw
- **Mixins:** `compat.aaa_particles.EffekRendererIdleSkipMixin` (the patch), `EffekseerManagerPlayTrackMixin`,
  `AaaDeltaTrackerFrameMixin`, `EffectDefinitionAccessor` (helpers). Config file: `flyaway_perf.compat.mixins.json`.
- **Target:** `mod.chloeprime.aaaparticles.client.render.EffekRenderer#draw(Type, float, PoseStack, Matrix4f, Camera)`.
- **Problem:** aaa_particles hooks `LevelRenderer.renderLevel` RETURN. With Iris on NeoForge the hook draws the
  WORLD effect layer at the end of every world render: the main pass and every Immersive Portals pass. The two
  first-person layers are drawn once per frame. Each draw costs the same whether or not any effect exists:
  - a depth blit from main to the particles target,
  - a full colour and depth blit from main to `DISTORTION_BACKGROUND` (`RenderUtil.prepareBackgroundBuffer`, 0.53%),
  - about 10 `glGetInteger` round trips plus a sampler save and restore,
  - stream pipelines over every registered effect,
  - the native `BeginUpdate/Update/DrawBack/DrawFront/EndUpdate` calls.

  In total, `EffekRenderer.draw` takes **1.17%** (0.72% in the main pass, 0.46% in portal passes).
- **Fix:** the mixin cancels the draw only when all four of these hold:
  - the manager reports `GetTotalInstanceCount() == 0`,
  - no `EffectDefinition` holds an emitter of any type. Checking every type means the WORLD draw still runs and cleans
    up first-person emitters while any exist.
  - nothing was `Play()`ed on that manager in the last 120 frames (`EffekseerManagerPlayTrackMixin`),
  - aaa_particles is not shut down.

  All GL state that the skipped code changes is restored by that same code, so skipping it is invisible. As soon as
  an effect plays, the next draw runs normally.
- **Expected gain:** up to about 1.1% when no Effekseer effect is alive, which is the normal case. Zero when effects
  are playing.
- **Risk:** low. If an addon calls `EffekseerManagerCore.Play` directly without going through `createParticle` and the
  native instance counter stays 0, that effect is not updated. No such caller exists in the pack. Any exception in the
  check falls back to the original draw.
- **Disable:** `mixin.compat.EffekRendererIdleSkipMixin=false`

### 2. aaa_particles: advance effects once per frame, not once per portal pass (bug fix and CPU)

> Lead note (review): this is an intentional *visible* change - with a portal on screen, Effekseer effects used to
> run N times too fast (N = render passes); they now run at normal speed. Disable with
> `mixin.compat.EffectDefinitionPortalDeltaMixin=false` to restore the old behaviour.

- **Mixin:** `compat.aaa_particles.EffectDefinitionPortalDeltaMixin` (`@WrapMethod`), using `AaaDeltaTrackerFrameMixin`.
- **Target:** `EffectDefinition#draw(Type, Vector3f, Vector3f, int, int, float[], float[], float, float, RenderTarget)`.
- **Problem:** `deltaFrames` is computed once per frame (`DeltaTracker.run`, from `Minecraft.runTick`), but `draw`
  runs once per world pass. In frames that render portals, `manager.update(delta)` and every emitter's
  `internalUpdateProgress(delta)` ran N times, so the effects played N times too fast. The native `Update` is
  0.23% self.
- **Fix:** the first draw of a manager in a frame passes the real delta. Later draws in the same frame pass 0: they
  still call `Update`, so per-pass layer and culling state is refreshed, and they still draw with that pass' camera.
  If the frame counter is not running (helper mixin disabled), nothing is changed.
- **Expected gain:** about 0.1-0.2% in portal frames. It also fixes the speed-up bug, which is the intended visual
  difference: effects seen while a portal is on screen no longer run 2x+ too fast.
- **Disable:** `mixin.compat.EffectDefinitionPortalDeltaMixin=false`

### 3. vanilla: chunk cache for `doAnimateTick`'s ambient-particle biome lookup - DROPPED AT MERGE

> Lead note: the same optimisation was written independently as `vanilla.clienttick.ClientLevelAnimateTickBiomeMixin` (xaero_hud_tick.md #5); only that one ships.

- **Mixin:** `vanilla.particles.ClientLevelAnimateTickBiomeMixin` (`flyaway_perf.vanilla.mixins.json`, client).
  Helper: `dev.flyaway.perf.vanilla.particles.AnimateTickBiomeCache`.
- **Target:** `ClientLevel#animateTick(III)V` (HEAD/RETURN) and the `getBiome(BlockPos)` call in
  `ClientLevel#doAnimateTick`.
- **Problem:** every client tick, `animateTick` calls `doAnimateTick` 2 x 667 times. Each call whose block is not a
  full collision cube calls `getBiome(pos)` to fetch the biome's ambient particle, which is empty outside the Nether
  biomes. Each lookup goes through `Level.getChunk` and then `ImmPtlClientChunkMap.getChunk`, which allocates a lambda,
  checks the thread and does a `Long2ObjectOpenHashMap` lookup. `doAnimateTick` is **1.70%**: `getBiome` 1.01%, of
  which 0.80% is the chunk lookup.
- **Fix:** while one `animateTick` call runs, `getBiome` goes through a `BiomeManager` built with
  `level.getBiomeManager().withDifferentSource(cache)`, so the zoom code and seed are vanilla's. Its noise source is a
  64-slot open-addressing chunk cache whose miss path is exactly `LevelReader#getNoiseBiome`:
  `getChunk(BIOMES, false)`, or `getUncachedNoiseBiome` when that returns null. About 1334 chunk-map lookups per tick
  become about 25.
  - The biome result, random-number consumption and particles are identical.
  - Chunks cannot load or unload inside one `animateTick` call: that only happens during packet handling on the same
    thread.
  - The cache is cleared at RETURN.
  - Calls to `doAnimateTick` from outside `animateTick` take the original path.
- **Expected gain:** most of the 0.8% chunk-lookup share, about 0.5-0.8%.
- **Risk:** very low. Another mod mixing into `LevelReader#getNoiseBiome` would be bypassed for this one call site;
  no such mixin was found in the pack.
- **Disable:** `mixin.vanilla.ClientLevelAnimateTickBiomeMixin=false`

### 4. Colorwheel: skip empty translucent Flywheel passes
- **Mixins:** `compat.colorwheel.ClrwlSkipEmptyTranslucentMixin`, plus `ClrwlEngineAccessor` and
  `ClrwlIndirectDrawManagerAccessor`.
- **Target:** `ClrwlSafeFlwImpl#submitTranslucentRenderContext` and `#submitShadowRenderContext` (phase TRANSLUCENT only).
- **Problem:** Colorwheel draws translucent Flywheel instances in a separate pass, calling
  `renderDispatcher().afterEntities(ctx)`, which is a full `VisualizationManagerImpl.render`:
  - a task sync and a GL state save and restore,
  - with Voxy, Voxy's combined-depth bracket around it (`VoxySableDepthShim.beginInPlace/endInPlace`: 3 depth copies,
    a merge blit, a restore blit and about 12 `glGet*` calls).

  After all that, `ClrwlIndirectDrawManager.renderTranslucent` returns immediately when no culling group has
  translucent or OIT draws. Create's instances are solid, so that is the usual case.
  `colorwheel$injectRenderTranslucents` is **0.68%**: 0.03% is Colorwheel's own work and about 0.56% is the Voxy
  bracket.
- **Fix:** the submit is skipped when every Colorwheel engine meets all three conditions:
  - it uses the indirect backend,
  - it has already run this frame's `prepareFrame`,
  - none of its culling groups has translucent or OIT draws.

  Nothing would have been drawn, and the Voxy bracket is built to leave depth unchanged, so the output is identical.
  The instanced backend is never skipped.
- **Expected gain:** about 0.6%, whenever no translucent Flywheel instance is loaded.
- **Disable:** `mixin.compat.ClrwlSkipEmptyTranslucentMixin=false`

### 5. Ponder/Catnip: memoise placement-helper item predicates
- **Mixin:** `compat.ponder.PlacementClientItemMatchCacheMixin` (`@WrapOperation`).
- **Target:** the `IPlacementHelper.matchesItem(ItemStack)` call in `net.createmod.catnip.placement.PlacementClient#checkHelpers`.
- **Problem:** every client tick with the crosshair on a block, `matchesItem(held)` is called on every registered
  placement helper (Create plus all addons) for both hands. Each call builds a new predicate
  (`getItemPredicate().test`) through `Predicate.and` chains and Registrate entry lookups. `checkHelpers` is
  **0.31%**, of which `matchesItem` is 0.19%.
- **Fix:** each helper's result is cached per unchanged held stack (same item, count and components, checked with
  `ItemStack.matches` against a private copy), with one slot per hand. Entries expire after 20 ticks, so a predicate
  that depends on anything other than the stack is at most 1 s stale. None is known: every helper in the pack tests
  only the item. Sable's mixin into `PlacementClient` targets a different method (`drawDirectionIndicator`).
- **Expected gain:** about 0.15-0.19%.
- **Disable:** `mixin.compat.PlacementClientItemMatchCacheMixin=false`

## Analysed and rejected (with reasons)

| Hotspot | Finding | Decision |
|---|---|---|
| `ClrwlIndirectPrograms$PipelinePrograms.get` → `HashMap.computeIfAbsent` 0.45% + 0.23% | Not lookup cost. These are the **shader compilations** (glsl-transformer parse and print of the Solas gbuffer programs) on a cache miss. Programs are cached per `IrisRenderingPipeline`, and broken shaders are cached too. The cost is one-time per pipeline: a new dimension or a shader reload, including the first view of another dimension through an Immersive Portals portal. Steady-state hashing of the key is about 0.06%. | Rejected: one-time stutter. Sharing programs across pipelines is unsafe. |
| Colorwheel shadow pass (`submitShadowRenderContext` 1.94%) | About 0.7% is `prepareFrame` (first engine call of the frame: `StagingBuffer.flush`, `glFenceSync`, mesh uploads), so it is not duplicated by the main pass. The rest is GPU culling dispatches and indirect draws, which are needed for Flywheel shadows. | Nothing CPU-side to remove. |
| `ClrwlIndirectCullingGroup.submitSolid` per-draw key alloc and `programs.get` | About 0.06% of hashing; the rest is GL (`bind`, `glMultiDrawElementsIndirect`, framebuffer binds). | Too small to justify the invalidation risk. |
| Flywheel `WaitGroup.await` 0.26% | The render thread spins until the worker threads finish the frame/tick plans. It already steals tasks (`syncOneTask`). | Real waiting; cannot be reduced without making the visuals faster. |
| Voxy combined-depth bracket on the **solid** Flywheel pass (0.39%) | Voxy area. The bracket is needed whenever there are solid instances. | Left to the Voxy owner. Patch 4 removes the translucent copy. |
| `SingleQuadParticle.getRenderBoundingBox` 0.40% self | The time sits on the first touch of each particle object (cache miss on `x/y/z/quadSize`), not on the AABB allocation. Any replacement test reads the same fields. Frustum-culling and building every particle would likely cost more. | Rejected: no gain possible without changing which particles are drawn. |
| `ParticleEngine.tick` 0.70% | Already async/cached by particle_core; the remainder is real particle physics (Sable sub-level collision checks). | No change. |
| aaa_particles portal-pass draw (0.46%) | Drawing is needed for correct output inside portals. Only the duplicate simulation was removed (patch 2). | Kept. |
| Simulated `EndSeaShadowRenderer.renderShadowMap` 0.57% | Re-renders all sub-levels and runs the 5-iteration `spread_end_sea` Veil pipeline every frame in dimensions with End-sea physics. The pipeline could only be skipped while no sub-level exists, but the user's world has ships. | Rejected: no gain for this pack. |
| Catnip ghost-block preview 0.32% | Re-tessellates the previewed block with AO every frame. Caching would have to track light and neighbours. | Rejected: visual, not worth the risk. |
| Block entity renderers other than spawners (skull 0.27%, sign 0.13%, chest 0.10%) | Vanilla model building. Vanillin already instances chests; the remaining chests are in the GUI, Lootr chests and similar. Sodium's per-BE culling self time is 0.07%. | Nothing material. The spawner renderer belongs to another area. |
| fallingleaves | Not visible in the profile. | No change. |

## Config recommendations (no code)

Optional; these change visuals or behaviour, so they are the user's call:
- `config/particle_core_config.toml`:
  - `particleRenderDistanceMultiplier = 1.0` is the default. 0.75 drops distant particle geometry and frustum
    tests; it is a visual change.
  - `asynchronousTicking = true` is already on.
- `config/flywheel-client.toml`:
  - `limitUpdates = true` is already on and throttles distant visual updates.
  - `workerThreads = -1` (auto) is good on 16 hw threads.
- `config/aaa_particles-client.toml`: `[gc] enabled = false` keeps all loaded effects in memory. That is fine with
  32 GB, but the system's swap is almost full, so `enabled = true` would free unused effect memory; that is a memory
  trade-off, not CPU.
- `config/colorwheel-client.toml`: keep `enableFallbackMode = false`. The indirect backend is the one patch 4 targets
  and the fastest.

## What the lead should test

1. The log shows all mixins applied, with no `InvalidInjectionException` or `MixinApplyError` for
   `flyaway_perf.compat.mixins.json` or `flyaway_perf.vanilla.mixins.json`.
2. aaa_particles:
   - With no effect playing, `EffekRenderer.draw` should vanish from a spark profile.
   - Trigger an Effekseer effect (for example an Epic Fight or Iron's Spells skill that uses aaa_particles). It must
     appear immediately, and the first-person (hand) effects must still render.
   - With a portal on screen, effects keep normal speed.
3. Particles: ambient Nether particles (crimson spores, ash, warped spores) still spawn. Overworld unchanged.
4. Colorwheel with Solas: Create contraptions, belts and shafts render in the main and shadow passes. A translucent
   Flywheel instance (for example Create's glass on a contraption, if any) must still appear. Check Voxy LOD terrain
   behind contraptions.
5. Create placement helpers (holding shafts or cogs and looking at a shaft) still show the ghost preview and arrow.
