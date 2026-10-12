# Entity rendering: Epic Fight, its addons, equipment lookups (`epicfight`, `compat.*`, `vanilla.entity`)

Profile: `user-profile-s4gidwMiAz.sparkprofile`, render thread only, 92.6 s. All percentages below are of the
render thread.

## Where the time goes (summary)

| Inclusive | What |
|---|---|
| 10.66% | `EntityRenderDispatcher.render` (all passes; the Iron's `handleTransmog` / Accessories `adjustArmorLookup` / ModernFix `fireCheckingPoseStack` wrappers are just wrappers, ~0 self) |
| 5.41% | Epic Fight `RenderEngine.epicfight$renderLivingPre` -> `PatchedLivingEntityRenderer.render` (main pass only; 0.02% portal, 0.2% shadow) |
| 2.7% | of that: `ComputeShaderSetup.draw` (one per skinned mesh / armor piece): Iris `ExtendedShader.apply` 0.88% (glBindFramebuffer 0.47%, uniforms 0.29%), `clear` 0.33% (main RT re-bind), compute dispatch 0.5%, glDrawArrays 0.43%, vertex format binding 0.13% |
| 0.39% | `DynamicSSBO.updateFromTo` (glBufferData orphaning of the pose + hidden-flag SSBOs, 2 per draw) |
| 0.47% | `setArmaturePose` (animation sampling, one per entity per frame - no multi-pass duplication) |
| 2.75% | Iris `ShadowRenderer.renderPlayerEntity`: the local player in the shadow map. It is drawn almost entirely with the **vanilla** player model (2.15%; Epic Fight's own armature path is only 0.2% here), so the cost is vanilla model + skinlayers3d + NEA + layers. Aether's parallel-stream cape check is part of it (lead already patched `compat.aether`). |
| 1.94% | `SpawnerRenderer.renderEntityInSpawner`: the spinning mob in mob spawners. Display entities never get an Epic Fight patch (they never join the level), so this is plain vanilla model rendering of mobs with armor/held items. |
| 1.77% | Curios, of which 0.95% is Iron's Spellbooks' `CurioBaseItem.isEquippedBy` called from its `Entity.isInvisibleTo` hook (Xaero radar 0.52%, name tags 0.28%, Gliders layer 0.12%) |
| 0.58% | Gliders (`vc_gliders`) - 0.39% in `getFirstFoundGlider` |
| 0.40% | `LivingEntity.getLookupToggle` "self" |

Epic Fight client tick (`LocalPlayer` / patch ticks) is ~0.05% - nothing to gain there.

### What the user's "renderpatch" Epic Fight build changed (vs upstream 1.21.1 branch, 21.17.3)
Only 7 classes differ from upstream (by timestamp in the jar; confirmed by diffing against
github.com/Epic-Fight/epicfight branch `1.21.1`):
* `DynamicSSBO`: upload with `glBufferData` orphaning instead of `glBufferSubData` into a buffer still in use.
* `ArrayProxyPool`: simple round robin, the GL fence-sync per buffer was removed (no CPU/GPU sync stalls).
* `ComputeProgram`: uniform locations cached in a map (upstream queried `glGetUniformLocation` each call).
* `ComputeShaderSetup`, `ComputeShaderProvider`, `WearableItemLayer`, `HealthBar` were also rebuilt (not diffed in
  detail; the hot paths we patch - `ComputeShaderSetup.draw`, `bindBufferFormat`, `DynamicSSBO` - were read from the
  shipped jar's bytecode).

The profile matches the patched code (e.g. `nglBufferData` under `DynamicSSBO.updateFromTo`), i.e. the profile was
taken with this build. Our patches are written against it.

## Patches

### 1. `compat.curios.CurioInventoryCapabilityKeyMixin` (common) - DROPPED AT MERGE

> Lead note: superseded by the identical `compat.curios.CurioInventoryItemKeyMixin` (see xaero_hud_tick.md #3), which covers both `findFirstCurio(Item)` and `findCurios(Item)`. Not shipped.

* Target: `top.theillusivec4.curios.common.capability.CurioInventoryCapability#findFirstCurio(Item)`
* Problem: computes `Curios.itemCacheKey(item.getDefaultInstance())` on every call: a new ItemStack (whose
  constructor runs owo's derived-component hook and Accessories' `restorePatch` hook, both with streams) and a stream
  that `toString()`s and concatenates every default data component - only to look up Curios' own per-game-tick result
  cache. 0.65% of the render thread (inside the 0.95% `isEquippedBy`), plus server-thread calls.
* Fix: compute the key once per `Item` (`dev.flyaway.perf.compat.curios.ItemCacheKeys`, ConcurrentHashMap - the method
  runs on both threads) and call the same `findFirstCurio(predicate, key)`. Default components are fixed after registry
  freeze, so the key string is identical. Behaviour identical.
* Expected gain: ~0.6% render thread (+ server).
* Risk: very low. If some default component's `toString()` were identity based, the original key would differ on every
  call (Curios' cache never hit and grew); now it is stable - the intended Curios semantics.
* Disable: `mixin.compat.CurioInventoryCapabilityKeyMixin=false`

### 2. `compat.irons_spellbooks.CurioBaseItemEquippedCacheMixin` - DROPPED AT MERGE

> Lead note: superseded by `compat.irons_spellbooks.CurioBaseItemEquippedMemoMixin` (xaero_hud_tick.md #4). Not shipped.

* Target: `io.redspace.ironsspellbooks.item.curios.CurioBaseItem#isEquippedBy(LivingEntity)` (`@WrapMethod`)
* Problem: Iron's injects into `Entity.isInvisibleTo(Player)` and asks `INVISIBILITY_RING.isEquippedBy(player)` every
  time; Xaero's radar does this for every tracked entity, name-tag checks and Gliders' layer for every rendered entity -
  always for the local player. Capability lookup + Optional chain + Curios cache lookup per call.
* Fix: per (item, entity) memo valid for one client "epoch" (see `FrameStamp` below). Only on the client thread and for
  client-side entities; server-thread calls (spell logic) always run the original.
* Why exact: Curios inventories on the client only change via packets (processed at `runTick` start), the client tick
  and keybinds, and GUI clicks (dispatched in `pollEvents` after rendering) - the epoch is bumped at all of those points.
  Curios itself already caches this exact lookup per game tick.
* Expected gain: the remaining ~0.25% of `isEquippedBy` after patch 1.
* Disable: `mixin.compat.CurioBaseItemEquippedCacheMixin=false`

### 3. `compat.vc_gliders.CuriosTrinketsUtilGliderCacheMixin` (common, active only on the client thread)
* Target: `net.venturecraft.gliders.common.compat.trinket.CuriosTrinketsUtil#getFirstFoundGlider(LivingEntity)`
* Problem: Gliders asks "which glider does this entity wear" from `Block.animateTick` (every animated block each tick),
  `RenderLevelStageEvent` (every stage of every pass), its player layer, an ItemInHandLayer hook and the player tick.
  Each call checks the chest slot then 3 Curios slots (capability lookup + ArrayList per slot). 0.39%.
* Fix: memo keyed on (entity identity, client epoch, identity of the entity's current chest ItemStack). The chest check
  keeps it exact when Accessories substitutes a cosmetic chest item during entity rendering (`getItemBySlot` returns a
  different stack while the lookup toggle is on). Returns the same live ItemStack reference the original would.
* Expected gain: ~0.3%.
* Disable: `mixin.compat.CuriosTrinketsUtilGliderCacheMixin=false`

### 4. `vanilla.entity.MinecraftFrameStampMixin` (client) + `dev.flyaway.perf.vanilla.entity.FrameStamp`
* Targets: `Minecraft#runTick` HEAD, `Minecraft#tick` HEAD/RETURN, `Minecraft#handleKeybinds` RETURN (method itself, not
  its call site: Simply Swords `@Redirect`s that call and Carry On wraps it).
* Provides the epoch counter + client-thread identity for patches 2 and 3. Pure counter, no behaviour.
* If disabled (`mixin.vanilla.MinecraftFrameStampMixin=false` or `area.vanilla=false`) patches 2/3 silently fall back
  to the original code.

### 5. `epicfight.ComputeShaderSetupVaoStateMixin` (client)
* Target: `yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup#draw` - wraps the
  `bindBufferFormat(VertexFormat)` call and the final `VertexFormat.clearBufferState()`.
* Problem: every skinned-mesh draw re-specifies up to 9 vertex attribute pointers + enables (with 2 buffer binds) and
  at the end disables up to 15 attribute arrays - all state of the mesh's **private** VAO (`arrayObjectId`, bound only
  by `drawWithShader` around `draw`), pointing at buffers that never change for the setup's lifetime. 0.13%+.
* Fix: remember the VertexFormat the VAO is configured for; same format -> skip both; different format (e.g. glint /
  outline / shadow shader) -> disable the old format's arrays exactly like the original end-of-draw did, then bind the
  new one. The GL state at glDrawArrays is identical; the left-enabled arrays only exist inside Epic Fight's own VAO.
* Expected gain: ~0.15%.
* Risk: low; any mod that binds Epic Fight's VAO itself would see enabled arrays (none does).
* Disable: `mixin.epicfight.ComputeShaderSetupVaoStateMixin=false`

### 6. `epicfight.DynamicSSBOUploadDedupMixin` (client)
* Target: `yesman.epicfight.client.renderer.shader.compute.backend.ssbo.DynamicSSBO#updateFromTo` - wraps the
  `GL15C.glBufferData(int, FloatBuffer, int)` call.
* Problem: with `use_persistent_buffer = false` (the pack's setting) each draw orphans + re-uploads a pooled
  hidden-part-flags SSBO (1-8 ints, almost always all zero) - one of the two glBufferData per draw (0.39% total).
* Fix: only for `srcSize == 1` buffers (the hidden-flag pool; the pose pool changes every frame and is untouched):
  remember the raw bits last uploaded into this buffer; skip the glBufferData if identical. The compute shader declares
  the buffer `readonly`, so its GPU content is exactly those bytes. Partial updates invalidate the memo.
* Expected gain: ~0.15-0.2%.
* Disable: `mixin.epicfight.DynamicSSBOUploadDedupMixin=false`

### 7. `epicfight.LayerClassLookupMixin` (client)
* Targets: `PatchedLivingEntityRenderer#render/#renderLayer`, `FirstPersonRenderer#renderLayer`,
  `PresetRenderer#renderLayer` - the `Class.isAnonymousClass()` calls.
* Problem: per entity per frame, for every vanilla layer, Epic Fight calls the uncached native reflection
  `isAnonymousClass()` to find the patched layer. 0.05%.
* Fix: `ClassValue` cache (`dev.flyaway.perf.epicfight.AnonymousClasses`). Identical answers.
* Disable: `mixin.epicfight.LayerClassLookupMixin=false`

Total expected: ~1.4-1.6% of the render thread (most of it in Curios/Iron's/Gliders lookups that are paid per
entity, so more with many entities / Xaero radar entities on screen).

## Rejected / not done (and why)

* **Cache armature poses across passes (main/shadow/portal).** The profile shows Epic Fight renders its entities
  essentially only in the main pass (0.02% portal, 0.2% shadow - the shadow player is drawn with the vanilla model),
  and `setArmaturePose` runs once per entity per frame. Nothing to share.
* **Batch Epic Fight compute draws / defer `ExtendedShader.clear()` between consecutive meshes.** This is where the
  real Epic Fight cost is (apply 0.88% + clear 0.33% per mesh), but skipping apply/clear needs the next draw to use the
  same shader *and* no foreign GL draw in between, and Iris updates per-object uniforms (e.g. `entityId`) in `apply()`.
  Too easy to get wrong silently (wrong framebuffer / stale uniforms) - not "zero visual change" by construction.
  Better handled inside Iris (redundant framebuffer bind elision would cut the alternating
  `apply()`->bind gbuffer / `clear()`->bind main RT pairs only partially). Note: the decompiler shows
  `ExtendedShader.apply()` calling `bindFramebuffer()` twice; the real bytecode calls it once (checked with javap).
* **Reuse the previous draw's pose SSBO when content is equal** (body -> armor pieces of the same entity share the
  joint matrices): the per-part matrices appended after the joints differ per mesh, so equality is not guaranteed and
  partial updates of a buffer still read by an in-flight dispatch would need syncs. Not worth the risk; the config
  route below removes all these uploads anyway.
* **Spawner mobs (1.94%).** It is vanilla model rendering of the spinning display entity (animated with partial ticks).
  Caching geometry or skipping layers would change visuals. No safe CPU-only win.
* **`LivingEntity.getLookupToggle` 0.40% "self".** It is Accessories' `getItemBySlot` HEAD hook on every Mob/Player
  (`CosmeticArmorLookupTogglable.getAlternativeStack`), called from dozens of places; the self time is mostly the
  inlined hook + CallbackInfoReturnable of the very hot `getItemBySlot`. A trivial getter cannot be made cheaper and the
  hook itself cannot be removed without touching Accessories' mixin. Diffuse; skipped.
* **Accessories `setLookupToggle(false)` capability lookup (0.04%)** - would need `@Shadow` of a field Accessories adds
  to `LivingEntity` via its own mixin; too fragile for the gain.
* **holdmyitems `onRenderArmWithItem` (0.30% self)**: a 2000-line first-person animation handler (config gets, string
  ops per hand per frame). Rewriting it is out of scope for a "no visual change" patch.
* `AttributeMap.getValue` (0.43%): 0.19% is Immersive Portals collision (`CollisionHelper.getStretchedBoundingBox ->
  ScaleUtils.getScale`), rest is diffuse vanilla `getScale`/attribute reads - belongs to `imm_ptl`.
* Epic Fight server side: the profile covers only the render thread; nothing hot from Epic Fight's client tick
  (~0.05%).

## Config-only recommendations (for the lead to test)

* `config/epicfight-client.toml`: `use_persistent_buffer = true` (currently `false`). With persistent mapping Epic Fight
  writes poses / hidden flags straight into a 16 MB persistently mapped ring buffer and never calls glBufferData per draw
  (the whole 0.39% `DynamicSSBO.updateFromTo`). Requires GL 4.6 (checked by Epic Fight). The user's renderpatch build
  tuned the non-persistent path, so they may have turned this off on purpose (driver issue?) - test for flicker/corrupt
  limbs first. Patch 6 is what we do if it stays `false`.
* `config/skinlayers.json`: `renderDistanceLOD` 14 -> e.g. 12 would cut 3D skin layer meshing for far players
  (skinlayers3d `CustomizableModelPart.compile` 0.2% self, 0.35% in the shadow pass). This *is* a (small, distant)
  visual change - only if the user accepts it.

## What the lead must test
* All 7 mixins apply (log: no `InvalidInjectionException`; `flyaway_perf` applied-count).
* Epic Fight mobs/players with armor, armor trims, enchanted (glint) armor, glowing outline (spectral arrow) and
  first-person arms (hidden parts) render exactly as before - with and without the Solas shader (Iris on/off switches
  between the vanilla and Iris compute setups and vertex formats).
* Iron's invisibility ring: equip/unequip it - invisible mobs become visible immediately; name tags/Xaero radar fine.
* Gliders: equip a glider in chest and in the Curios back slot; glider render + updraft particles unchanged. With an
  Accessories cosmetic chest item equipped the glider layer behaves as before.

## Fracture blocks (Epic Fight ground slams)

Profile `spark/76w5vCPcjb.bin` (render thread, taken during a fight): `FractureBlockRenderer.render` **7.13%** of the
render thread (all in the main pass - the Iris shadow pass and portal passes do not draw it), of which:

| Inclusive | What |
|---|---|
| 7.08% | `BlockRenderDispatcher.renderBreakingTexture` (aeronautics `wrapMethod` = levitite pose nudge, colorwheel `injectBeginEndBlock` = Iris block-id begin/end - both thin wrappers) -> `ModelBlockRenderer.tesselateBlock` -> NeoForge `LightPipelineAwareModelBlockRenderer.tesselateWithAO` (vanilla AO path, experimental pipeline off) |
| 5.84% | `AmbientOcclusionFace.calculate` |
| 4.36% | of that `ModelBlockRenderer$Cache.getLightColor` -> `LevelRenderer.getLightColor`: LambDynLights `onGetLightmapCoordinates` 2.0% (incl. its own `getBlockState().isSolidRender`), light engine `getBrightness` x2 1.87%, micimmptl `foreignSkylight` 0.40% |
| 0.32% / 0.31% | `getBlockState` (Immersive Portals client chunk map) / `Cache.getShadeBrightness` |
| 0.87% | `putQuadData` -> `BufferBuilder.putBulkData` (Iris extended vertex data) |

### What the renderer really does
Client only. A slam (`LevelUtil.circleSlamFracture`; the server sends one `SPCreateTerrainFracture` packet, the client
repeats the shockwave) does `level.setBlock(bp, FractureBlockState, 0)` for each ground block of the disc; the
fracture state is `RenderShape.INVISIBLE` (the chunk mesh skips it) and has a block entity. Every frame
`FractureBlockRenderer.render` builds the bounce/rotation pose and calls
`renderBreakingTexture(originalState, bp.above(), level, pose, buffers.getBuffer(RenderType.cutout()), ModelData.EMPTY)`.
Despite the name this is **not** the crumbling overlay: `renderBreakingTexture` = `tesselateBlock(level, model, state,
pos, pose, consumer, checkSides = true, rand, seed, NO_OVERLAY, modelData, renderType = null)` - the original block's
full model (all quads) into the normal cutout buffer, with smooth lighting and face culling evaluated at
`bp.above()`. Per block per frame: ~6 AO faces x (up to 9 light + 9 shade + ~15 getBlockState) world reads, almost all
on positions shared with the neighbouring fracture blocks. Lifetime is `30 + rand(radius*80)` ticks (a Meteor Slam /
big NightFall slam keeps hundreds of fracture blocks alive for seconds), so the per-frame cost scales with the number
of blocks - this is the "freeze".

### Patch 8: `epicfight.FractureBlockRendererLightMemoMixin` (client) + `vanilla.epicfight.*` (client)
* `epicfight.FractureBlockRendererLightMemoMixin`: `@WrapOperation` of the `renderBreakingTexture(...)` call in
  `FractureBlockRenderer#render(FractureBlockEntity,float,PoseStack,MultiBufferSource,int,int)` - sets
  `FractureLightMemo.inFracture` around the unchanged call.
* `vanilla.epicfight.ModelBlockRendererCacheFractureMemoMixin` (`ModelBlockRenderer$Cache`): `@WrapOperation` of the
  `LevelRenderer.getLightColor(BlockAndTintGetter,BlockState,BlockPos)` call in `getLightColor` and of the
  `BlockState.getShadeBrightness(BlockGetter,BlockPos)` call in `getShadeBrightness`.
* `vanilla.epicfight.AmbientOcclusionFaceFractureMemoMixin` (`ModelBlockRenderer$AmbientOcclusionFace`):
  `@WrapOperation` of all 15 `BlockAndTintGetter.getBlockState` calls in `calculate`.
* `vanilla.epicfight.LevelRendererFracturePassMixin`: `LevelRenderer#renderLevel` HEAD/RETURN -> memo epoch.
* `vanilla.epicfight.GameRendererFractureFrameMixin`: `GameRenderer#render(DeltaTracker,boolean)` HEAD -> force reset.
* Helper: `dev.flyaway.perf.epicfight.FractureLightMemo`.
* Fix: while (and only while) the fracture renderer's own call runs on the render thread inside a `renderLevel`
  pass, light / shade / block-state reads are memoised per (getter identity, position[, BlockState argument
  identity]) for the rest of that pass. Nothing in the client world changes during a pass: packets, ticks
  (LambDynLights recomputes its spatial lookup at the end of the level tick), block entity ticks and block changes run
  between frames, and `pollLightUpdates` / `runLightUpdates` run at the top of `renderLevel` before any block entity
  is drawn (the Iris shadow pass is later too: it is injected before `renderSky`). The epoch changes at every
  `renderLevel` HEAD and RETURN (a nested Immersive Portals pass runs light updates again -> fresh memo for it and for
  the rest of the outer pass) and at every frame start; the memo is dropped whenever a different getter is passed.
  The light key includes the state argument because vanilla passes the *rendered* (original) state for the centre
  sample and the level's state for the neighbours (emission / emissive rendering depend on it). So every memoised
  value is exactly what the original call returned earlier in the same pass - the vertex stream (positions, AO
  brightness, lightmap, colour, Iris extended data) is bit-identical. The other mods' wrappers (aeronautics,
  colorwheel, Sable getShade, moreculling, LambDynLights, micimmptl) still run; the light wrappers run once per
  position per pass (micimmptl's `probeBake` there is a debug-log counter only).
* Expected gain: the light/shade/state reads are 5.0% of the 7.13%; a disc of N fracture blocks touches ~3N distinct
  positions instead of ~100N reads -> roughly **-4% of the render thread** in that profile, and the frame-time spike
  of a big slam shrinks by a similar factor (~60-70%). Not touched (still per block per frame): AO maths + quad
  emission (~1.7%).
* Risk: low. The memo is inert outside `FractureBlockRenderer` (one static boolean check per AO sample elsewhere),
  only used on the thread that opened the pass, and capped (32k positions, then restarted).
* Disable: `mixin.epicfight.FractureBlockRendererLightMemoMixin=false` (whole feature off), or individually
  `mixin.vanilla.ModelBlockRendererCacheFractureMemoMixin=false`,
  `mixin.vanilla.AmbientOcclusionFaceFractureMemoMixin=false`, `mixin.vanilla.LevelRendererFracturePassMixin=false`
  (memo never active), `mixin.vanilla.GameRendererFractureFrameMixin=false` (removes only the safety reset).

### Patch 9: `epicfight.LevelUtilShockwaveEntityQueryMixin` (common; acts on the server thread)
* Targets: `LevelUtil#circleSlamFracture(LivingEntity,Level,Vec3,double,boolean,boolean,boolean)` HEAD/RETURN and the
  `Level.getEntities(Entity,AABB)` call in `LevelUtil#spreadShockwave`.
* Problem: the server casts one shockwave ray per perimeter cell (~8 x radius rays per slam) and every ray runs an
  entity AABB query only to fill `entityBeingHit`, which `circleSlamFracture` reads only when `hurtEntities` is true.
  NightFall's `WeaponTrailGroundSplitter` calls `circleSlamFracture(..., hurtEntities = false)` 3 x density x sub-steps
  times per tick while a trail is active (also its Yamato / scythe / Damocles / Heavy Rain fractures, Avalon):
  thousands of entity-section scans per tick on the integrated server thread for a discarded result.
* Fix: only on the server thread inside a `hurtEntities = false` slam, the query returns `List.of()`. The query has no
  side effects; the per-cell loop over an empty list does nothing; block destruction above the disc, packets and the
  client-side fracture are unchanged. `hurtEntities = true` slams (Epic Fight's own, NightFall/Avalon damage slams)
  are untouched.
* Expected gain: server thread only (not in the render-thread profile); proportional to the number of no-damage slams.
* Disable: `mixin.epicfight.LevelUtilShockwaveEntityQueryMixin=false`

### Creation freeze - what else was checked (no change needed / not changeable)
* Client creation itself is cheap: `handleFracture` -> `circleSlamFracture` is 0.03% of the render thread in the
  profile (setBlockState, sky light sources, lithium BE creation). One packet per slam, not per block.
* Section rebuilds: each `setBlock(..., flags 0)` marks the 27 surrounding sections dirty via
  `setBlocksDirty -> setSectionDirty(..., important = false)`; Sodium 0.8 schedules a plain **async** rebuild
  (deduplicated per section; IMPORTANT/blocking rebuilds need `playerChanged = true`). Sodium uploads in the profile:
  0.23%. The same happens when each block restores (random lifetimes -> spread over many ticks). Needed for the
  visuals (the chunk mesh must hide/show the ground block).
* Light: fracture states have different light properties (empty occlusion shape), so each conversion/restore queues a
  `checkBlock`; the client light engine processes them in `runLightUpdates` (0.03% total). The light at `bp` is read
  by the fracture's own AO (down face), so it cannot be skipped.
* Voxy re-ingests only on changes to air / Domum states - not triggered. VSS dirty-column marking is server-only.
* Particles: `GROUND_SLAM` spawns `radius*10` terrain + `radius*10` cosy-smoke particles, `createParticle` 1-4 per
  fracture block, and each block spawns one `GROUND_FRACTURE` terrain particle per tick in its last 10 ticks -
  visible, not changed.
* So the stall is the per-frame rendering of many fracture blocks (patch 8), plus server work for NightFall trails
  (patch 9). If a hitch remains at the moment of creation, the lead should capture spark with all threads
  (`/spark profiler start --thread * --interval 1`) around a large Meteor Slam to see the integrated server and
  Sodium worker threads.
* Rejected: **cache the tessellated mesh per fracture block across frames and replay it with the new pose**. The
  output depends on light (can change any frame: `pollLightUpdates` runs a slice of queued light tasks per frame,
  LambDynLights changes every tick), neighbour states (fracture neighbours restore at random ticks -> face culling and
  AO change), moreculling's culling, the model's ModelData/getQuads and block tint. Validating all that exactly every
  frame costs about what the memo already leaves; an "invalidate after N ticks" cache would show stale light/culling
  for up to N ticks (visual change). Also rejected: skipping the unused `LevelRenderer.getLightColor(level, pos)` that
  `BlockEntityRenderDispatcher.setupAndRender` computes for the ignored packedLight argument - 1 lookup per block
  (~0.03%), and many mods hook that dispatcher.

### What the lead must test (fracture blocks)
* How to trigger (`epicfight-client.toml` `ground_slams = true`, already on):
  * Epic Fight **greatsword dash attack** (battle mode, greatsword, sprint + attack): small client-only slam
    (radius 1.1).
  * **Meteor Slam**: `/epicfight skill add @s mover epicfight:meteor_slam`, jump from 10-30 blocks, use the skill
    while falling: radius `ln(fallDistance * impact)` -> large disc (best stress test).
  * **Demolition Leap**: `/epicfight skill add @s mover epicfight:demolition_leap`, hold, release: radius
    `chargeTicks * 0.05`.
  * NightFall weapons (Yamato / scythe skills, Damocles / Heavy Rain swords) for the `hurtEntities = false` server
    path (patch 9).
* Mixins apply (log: no `InvalidInjectionException`; applied count +6).
* Visual A/B (same spot, screenshots mid-bounce) with `mixin.epicfight.FractureBlockRendererLightMemoMixin=false` vs
  default: fracture blocks of grass, stone, logs, leaves, glass, a light-emitting block (glowstone: flat-light path,
  unaffected), in daylight, at night next to a torch, and while holding a torch (LambDynLights) - lighting/AO
  identical. Also with Solas on, and looking at fracture blocks through an Immersive Portals portal.
* Perf: spark the render thread during repeated Meteor Slams: `FractureBlockRenderer.render` should drop from ~7% to
  ~2-3%, `Cache.getLightColor` under it to a fraction of a percent.
* Patch 9: NightFall trail skill near mobs - blocks above the disc still break, mobs still take no damage from the
  trail; damage slams still hit.
