# Round 2 sweep: every render-thread hotspot in both profiles

Profiles (render thread only, 4 ms sampling):

* **A** = `user-profile-s4gidwMiAz.sparkprofile` (2026-10-11, 92.6 s, current mod set).
* **B** = `config/spark/profile-2026-10-03_10.04.23.sparkprofile` (2026-10-03, 40 s, portal-heavy scene). Some
  mods were missing here and some were older: no aaa_particles, LambDynLights or Gliders; Epic Fight was still
  CPU-skinning; there was an older micimmptl / Voxy shim.

Method: `tools/spark/Both.java` lists every method with **>= 0.15% self or >= 0.5% inclusive** in either profile:
810 methods, 117 of them with >= 0.15% self. Every self-time row was traced to its callers with
`tools/spark/Entry.java` (callers of the outermost matching frame) and `Callers`. The ~690 inclusive-only rows are
call-tree wrappers such as `GameRenderer.render`, mixin bridges and event dispatch. Their cost is the sum of the
leaf causes below. They are grouped by subsystem in the second table. All numbers are % of the render thread,
given as A / B.

```
J=mods/spark-1.10.124-neoforge.jar; javac -d /tmp/st -cp $J perf-mod/tools/spark/*.java
java -cp $J:/tmp/st Both  <A> <B>                      # the full candidate list
java -cp $J:/tmp/st Entry <prof> '<regex>' 20 3          # who enters a method (outermost match), 3 caller frames
```

## Patches taken in this round

| # | Mixin (area / json) | Target | Saving (A / B) |
|---|---|---|---|
| 1 | `imm_ptl.GcMonitorBeanListCacheMixin` (common, `flyaway_perf.imm_ptl.mixins.json`) | IP `GcMonitor.update()`: `@WrapOperation` on `ManagementFactory.getGarbageCollectorMXBeans()` | 0.14% / 0.18% |
| 2 | `imm_ptl.CollisionHelperNoGlobalPortalBoxMixin` (common, imm_ptl json) | IP `CollisionHelper.updateCollidingPortalForWorld`: `@WrapOperation` on `getStretchedBoundingBox(Entity)` | 0.24% / 0.10% |
| 3 | `compat.relics.EntityUtilsEquippedCurioMemoMixin` (common, `flyaway_perf.compat.mixins.json`) | Relics `EntityUtils.findEquippedCurio(Entity, Item)`: HEAD/RETURN inject | 0.19% / 0.64% |
| 4 | `compat.veil.ShaderProgramUniformMapMixin` (client, compat json) | Veil `ShaderProgramImpl.<init>(ResourceLocation)`: RETURN inject | 0.19% / 0.27% (most of it) |

Total: about **0.6% (A) / 1.1% (B)** of the render thread. Every patch is behaviour-identical by construction, as
argued below.

### 1. Immersive Portals GC monitor: cache the GC MXBean list
* **Problem**: `GcMonitor.update()` runs before every frame (IP's `PreGameRenderEvent`). It calls
  `ManagementFactory.getGarbageCollectorMXBeans()` to compare the collection counts with the previous frame. That JDK
  call goes through the PlatformComponent lookup and its streams, and builds a new `ArrayList` each time.
  Measured on JDK 21 / G1: **13.8 us per call**. Profile: `getPlatformMXBeans` 0.14% / 0.18%.
* **Fix**: return one `List.copyOf` of the first result. The JDK returns the *same bean objects* on every call
  (checked: element identity is equal across calls; only the list is new), and the set of GCs never changes at
  runtime. So IP's `WeakHashMap<GarbageCollectorMXBean, Long>` sees the same keys, and `getCollectionCount()` is
  still read live. IP only iterates the list.
* **Risk**: none known. **Disable**: `mixin.imm_ptl.GcMonitorBeanListCacheMixin=false`.

### 2. Immersive Portals collision: no stretched bounding box when the level has no global portals
* **Problem**: every client tick (and every integrated-server tick) `updateCollidingPortalForWorld` walks all
  entities. For each non-portal entity it computes `getStretchedBoundingBox(entity)`, which allocates two Vec3 and two
  AABB and reads `LivingEntity.getScale()`. That is an `AttributeMap` -> `AttributeSupplier` hash lookup, which misses
  the cache on every entity. The box is then only tested against `GlobalPortalStorage.getGlobalPortals(level)`, which
  is empty unless world wrapping or dimension stacking is used. Profile: 0.24% / 0.10%, of which 0.19% is the
  attribute lookup. This is the "AttributeMap.getValue 0.43% (IP CollisionHelper 0.19%)" candidate.
* **Fix**: `@WrapOperation` on the call, with `@Local(ordinal = 0) List<Portal> globalPortals` (the LVT confirms
  slot 2 is the only `List` local). If the list is empty, return a constant dummy AABB. The following loop over the
  empty list never reads it. `getStretchedBoundingBox` has no side effects: position and velocity reads, an
  attribute read whose dirty-recompute is idempotent, and AABB math. Portal entities take the other branch and are
  unchanged. With any global portal present, the original call is made.
* **Risk**: none known. **Disable**: `mixin.imm_ptl.CollisionHelperNoGlobalPortalBoxMixin=false`.

### 3. Relics: memo of `findEquippedCurio` on the client thread
* **Problem**: Relics injects at HEAD of `BlockStateBase.getCollisionShape(level, pos, ctx)`. For **every air
  block** queried with an entity collision context, `getAirCollisionShape` calls
  `findEquippedCurio(player, GLITCHY_MANTLE)`. Each call does a Curios capability lookup (a new wrapper), Optional
  plumbing and a scan of every equipped slot. Shoulder Surfing's camera ray (`ObjectPicker.clip`, every frame inside
  `ShoulderSurfingCamera.setup` -> `DynamicOffsets`) walks dozens of air blocks. Reliquified Artifacts' fog and
  night-vision hooks call it every frame as well. Profile B: `findEquippedCurio` **0.64%** (0.50% from the
  air-collision hook, plus the `getEquippedCurios` iterator 0.10%). Profile A: 0.19%.
* **Fix**: on the client thread only (`FrameStamp.onClientThread()`) and only for `Player`s (non-players return
  `EMPTY` immediately in Relics), remember up to 4 (item -> result) answers for one player until the next
  `FrameStamp.epoch` bump. FrameStamp is round 1's `vanilla.entity.MinecraftFrameStampMixin`; it bumps at frame
  start, around the client tick and after keybinds, which are all the points where the client thread can change
  inventories. The integrated server never touches client-side entities. So between two bumps a fresh scan returns
  the same `ItemStack` object. A remembered non-empty stack is re-validated before reuse (`!isEmpty()` and same item),
  and a remembered empty result is only reused when it is the `ItemStack.EMPTY` constant. A stack mutated in place
  therefore takes the original path. For comparison, Curios' own `findFirstCurio` cache is even coarser: one entry
  per game tick.
* **Risk**: very low (same assumption as round 1's Iron's / Gliders caches). If `MinecraftFrameStampMixin` is
  disabled, the memo is inert. **Disable**: `mixin.compat.EntityUtilsEquippedCurioMemoMixin=false`.

### 4. Veil: hash the shader program's uniform table
* **Problem**: `ShaderProgramImpl.uniforms` is an `Object2ObjectArrayMap`. Every vanilla `Uniform.set` on a Veil
  program wrapped as a `ShaderInstance` (Sable/Aeronautics/Create Simulated shaders, applied on every Sodium chunk
  layer and sub-level draw via `setDefaultUniforms`) goes through `UniformWrapper -> program.getUniform(name)`, which
  is a linear `String.equals` scan. Profile: 0.19% / 0.27%, almost all of it `Objects.equals` in
  `Object2ObjectArrayMap.get`.
* **Fix**: replace the still-empty map with an `Object2ObjectOpenHashMap` at the end of the constructor
  (`@Shadow @Final @Mutable`). The field is private. It is only used through get/put/clear/values() and the copy
  constructor in `applyProgram`, and its iteration order is never relied on (it is only iterated to free uniforms).
  The same objects are returned. Veil 4.5.1 is the loaded version; Sable's nested 4.3.2 has the same field.
* **Risk**: none known. **Disable**: `mixin.compat.ShaderProgramUniformMapMixin=false`.

## Full candidate table (self time >= 0.15% in A or B)

"R1" = already handled by a round-1 patch or round-1 config. "user" = already fixed by the user's own
micimmptl / Voxy shipdepth build. Rows are grouped by root cause; GL natives are attributed to their callers.

### GL / driver natives (cost is the GL call itself)

| Method | A self | B self | Main callers | Decision |
|---|---|---|---|---|
| `glBindFramebuffer` | 8.39 | 8.32 | Iris ExtendedShader apply/clear, Iris ClearPass/composite, Epic Fight compute meshes, Sable | Iris area (R1 lazy rebind / glUseProgram dedup; another agent is on Iris now). Not touched. |
| `nglGetQueryObjectiv` | 1.34 | **15.18** | IP `PortalRenderInfo.renderAndDecideVisibility:237` (sync occlusion read) | R1 `imm_ptl.PortalRenderInfoNoSyncWhenHiddenMixin` (on by default). This is the big B-only item. |
| `JNI.invokeV` | 5.93 | 1.39 | `glfwWaitEventsTimeout` (FPS limiter idle, 5.0%) + `glfwPollEvents` | Idle time / OS input, not work. |
| `JNI.invokePV`, `callPV` | 1.64 / 1.50 | 4.03 / 0.97 | `glfwSwapBuffers` (GPU-bound wait); Iris custom matrix uniforms | swap = GPU wait; uniforms = R1 Iris dedup. |
| `nglDrawElements`, `glDrawArrays`, `nglMultiDrawElementsBaseVertex`, `MultiDrawElementsIndirect(Count)` | 3.52 / 0.76 / 0.59 / 0.32 | 3.29 / 0.81 / 0.61 / 0.33 | all renderers | Real draws. |
| `glUseProgram` | 1.62 | 1.72 | Iris/Sodium/Epic Fight | R1 dedup inside Iris batches; rest is the Iris area. |
| `glClear` | 1.03 | 0.99 | Iris ClearPass, IP portal passes | R1 rejected (each pass clears a different FBO). |
| `glFenceSync` | 0.95 | 1.09 | Sodium postRender, Flywheel/Colorwheel staging, Voxy | R1 rejected (needed fences). |
| `nglBufferSubData` | 0.84 | 0.73 | Sodium arena uploads, Flywheel staging | Real uploads. |
| `glDispatchCompute` | 0.80 | 0.32 | Epic Fight skinning, Flywheel culling | Real work. |
| `nglTexSubImage2D` | 0.71 | 0.17 | `TextureManager.tick` animated sprites (0.47% frames + 0.28% interpolated) | Rejected, see "Animated textures" below. |
| `glBlitFramebuffer` | 0.64 | 0.09 | aaa_particles `prepareBackgroundBuffer` 0.42%, Sodium `blitToScreen` 0.23% | R1 `EffekRendererIdleSkipMixin` skips the idle case; the blit is needed when effects draw. |
| `glBindVertexArray` | 0.60 | 0.48 | Sodium/Flywheel/Epic Fight | R1 Epic Fight VAO state; rest are real binds. |
| `glUniform1f`, `glUniform1i`, `glUniform3f`, `nglUniformMatrix4fv/3fv` | 0.47 / 0.20 / 0.20 / 0.21 / 0.19 | 0.38 / 0.14 / 0.10 / 0.20 / 0.15 | Iris uniforms | Iris area (other agent). |
| `glGenerateTextureMipmap` | 0.43 | 1.01 | Iris `CompositeRenderer.setupMipmapping` | Iris composite (other agent). |
| `glTextureParameteri` / `glTexParameteri` | 0.38 / 0.15 | 0.33 / 0.17 | Veil `setFilterDSA` from `TextureStateShard` 0.32%, Voxy HiZ, Iris mipmapping | Rejected: a "same filter" cache cannot be exact, because `NativeImage._upload` (sprite animation, DynamicTexture) re-sets MIN/MAG raw on the bound texture behind `AbstractTexture`'s back every tick. |
| `nglGetIntegerv` | 0.37 | 0.53 | Xaero `MapLimiter.updateAvailableVRAM` (R1 throttled), Voxy/Veil one-offs | R1 / small. |
| `glBindTextureUnit`, `glBindTexture`, `glBindBuffer(Base)`, `glEnable/Disable` | 0.33 / 0.22 / 0.30+0.19 / 0.31+0.28 | similar | everywhere | R1 rejected (state not owned, many raw callers). |
| `nglBufferData` | 0.33 | 0.00 | Epic Fight `DynamicSSBO.updateFromTo` | R1 dedup + `use_persistent_buffer=true` config. |
| `glCopyTexSubImage2D` | 0.26 | 0.25 | Iris `FinalPassRenderer` | Iris area. |
| `glNamedFramebufferTexture`, `glCheckNamedFramebufferStatus` | 0.19 / 0.00 | 0.30 / **0.35** | Voxy HiZ mip chain; `VoxySableDepthShim.prepareDrawFramebuffer` | The B-only check is gone in A (user's newer shim caches twin FBOs: 0.01% in A). HiZ: R1 rejected. |
| `nglGetSynciv`, `nglDeleteSync` | 0.12 / 0.16 | 0.19 / 0.14 | Voxy Download/UploadStream fences | Real polling, tiny. |

### Java hotspots

| Method | A self | B self | Root cause | Decision |
|---|---|---|---|---|
| `ImmPtlClientChunkMap.getChunk` | 1.72 | 1.43 | IP chunk map | R1 `ImmPtlClientChunkMapFastGetMixin`. |
| `EventBus.post`, `SubscribeEventListener.invoke`, `ConsumerEventHandler.invoke` | 0.89 / 0.42 / 0.29 | 0.75 / 0.26 / 0.19 | NeoForge bus dispatch | R1 rejected (generic). |
| `Unsafe.unpark` | 0.75 | 0.60 | parallel-stream fork in Aether `isCapeVisible` (`ForkJoinTask.invoke` 0.95 / 0.77) | R1 `compat.aether.AetherCapeStreamMixin`. |
| `HashMap.hash`, `HashMap.getNode`, `HashMap.putVal`, `Objects.equals` | 0.72 / 0.45 / 0.41 / 0.34 | 0.54 / 0.53 / 0.39 / 0.40 | Colorwheel program cache misses (shader compiles, R1 rejected), vanilla `ShaderInstance.setSampler("Sampler"+i)` per draw (~0.4%), azurelib `replaceAnimatableTexture` (0.13%), **Veil uniform scan** (0.17%) | Veil: **taken (#4)**. `setSampler`: rejected, since the concat comes from an invokedynamic that a mixin cannot target without overwriting `setDefaultUniforms` (Iris, Veil and Sodium all hook it); about 0.1% gain. azurelib: a single HashMap probe per `getTexture`; rejected. |
| `VertexConsumer.addVertex`, `BufferBuilder.fillExtendedData`, `VertexConsumer.setColor` | 0.72 / 0.54 / 0.05 | 0.15 / 0.89 / 0.28 | model vertex emission; Iris extended format; B: Epic Fight CPU skinning | Real work. Iris internals out of scope. Epic Fight now uses compute skinning (A). |
| `xaero.map.*` (`onRenderProcess`, `MapPixel.getPixelColours`, `BiomeColorCalculator`, `Paletted2DFastBitArrayIntStorage.set`, `BranchLeveledRegion.get`, `MapBlock.getPixelColour`), `IdentityHashMap.get` (B, BlockColors), `BlockAndTintGetter.getBrightness`, `Long2ObjectOpenHashMap.get` (region hash) | 0.68 / 0.62 / 0.55 / 0.35 / 0.28 / 0.12 / 0.10 / 0.47 / 0.38 | 0.08 / 0.53 / 0.03 / 0.44 / 0.16 / 0.16 / 0.57 / 0.46 / 0.12 | world-map writer and colouring, time-budgeted | R1 rejected (budgeted, so a faster path just fills the map sooner). Region hash: R1 `MapRegionHighlightHashMixin`. |
| `Unsafe.allocateInstance` | 0.66 | 0.71 | lambda/object allocation spread over ~40 sites (none > 0.02%) | Diffuse, safepoint bias. Rejected. |
| `ConcurrentHashMap$TreeNode.findTreeNode` | 0.60 | 0.05 | VSS `LodRequestManager` colliding Long keys | R1 `vss.LodRequestManagerStrictKeysMixin`. |
| `Object2ObjectOpenHashMap.get` | 0.54 | 0.42 | `AttributeSupplier.getAttributeInstance` from IP `CollisionHelper.getStretchedBoundingBox` (0.15) + diffuse | IP part: **taken (#2)**. |
| `DeferredHolder.value` | 0.54 | 0.42 | ~30 call sites (Carry On, Curios, Iron's, Aether...), none > 0.04% | Diffuse. Rejected. |
| `Collections$UnmodifiableCollection.isEmpty`, `Reference2ReferenceOpenHashMap.get` (B), `LongAVLTreeSet.locateKey`, `EntitySection.getEntities` | 0.51 / 0.06 / 0.16 / 0.18 | 0.39 / 0.33 / 0.15 / 0.18 | entity-section box queries: micimmptl `SubLevelStraddle.portalsIn` (0.59), IP `getNearbyPortals` (0.19), IP colliding portals (0.09), spell/radar/raytrace | R1 `SubLevelStraddlePortalCacheMixin` (per-frame memo); `getNearbyPortals` R1 rejected (4 callers < 0.1%). |
| `SingleQuadParticle.getRenderBoundingBox` | 0.40 | 0.40 | NeoForge particle frustum test | **Re-checked, still rejected**: identical 0.40% in both profiles with different particle loads, and the self time is the first touch of each particle object (fields `x/xo/quadSize`). Any cull test must read the same fields, and skipping the test would draw more. |
| `LivingEntity.getLookupToggle` | 0.40 | 0.04 | Accessories `getItemBySlot` HEAD hook | R1 rejected. The getter is `level().isClientSide() ? field : false`, so the self time is the cache miss of the entity being asked for equipment. Nothing to remove. |
| `RenderStateShard.clearRenderState` / `setupRenderState` | 0.39 / 0.27 | 0.33 / 0.28 | render-type state lambdas (incl. `TextureStateShard` 0.76 incl.: `setFilter` 0.32, `getTexture` 0.12) | `setFilter`: rejected (see the GL texParameter row). Others are real state changes. |
| `java.util.ArrayList.<init>`, `Collection.removeIf` | 0.33 / 0.27 | 0.28 / 0.34 | Iris batched entity rendering segments, Sodium arena/ClonedChunkSectionCache | Iris internals (other agent) / Sodium bookkeeping. Rejected. |
| `Reference2ObjectArrayMap.get` | 0.32 | 0.10 | `DataComponentMap` lookups (vanilla patch map -> owo `DerivedComponentMap` -> patch map) | Rejected: 0.38% total for owo's extra indirection, but each lookup is already O(1) on a tiny map. A shortcut when the derived patch is empty saves one pointer chase (< 0.1%). |
| `ClassLoader.defineClass0/1` | 0.31+0.24 | 0.14+0.10 | first use of GUI classes (owo UI, Apothic Attributes GUI, FTB sidebar, creative tabs, tooltips, fabric FluidStorage `<clinit>`) | One-time class loading when screens are first opened. Pre-warming would run foreign static initialisers early (side effects, load order). Rejected. |
| `holdmyitems onRenderArmWithItem` | 0.30 | 0.19 | first-person animation handler | R1 rejected. |
| `ClientLevel.doAnimateTick` | 0.28 | 0.19 | ambient particles | R1 `ClientLevelAnimateTickBiomeMixin`. |
| `Uniform.markDirty` | 0.27 | 0.09 | vanilla `Uniform.set` from `setDefaultUniforms` | Iris doc R1 rejected (uniform dedup). |
| `Spliterator.getExactSizeIfKnown` + all `java.util.stream` (3.71 / 2.69 incl.) | 0.25 | 0.24 | Curios `itemCacheKey` 0.42 (R1), Aether cape 0.75 (R1), `CreativeModeTabs.buildAllTabContents` 0.25 (one-time on creative open), Jade `shouldRequestData` 0.17, Accessories `restorePatch` 0.17 (via Iron's ring path, R1 memo removes it), `FogRenderer.getPriorityFogFunction` 0.13, IP `GcMonitor` 0.14 (**taken #1**), presencefootsteps 0.11 | FogRenderer: rejected (its element type is a package-private interface that a mixin cannot name, so it would need an `@Overwrite` of a method 9 mods hook in the same class; 0.13%). Jade: see Jade row. |
| `Effekseer...Update`, `WaitGroup.await`, Sable `getAllIntersecting` | 0.23 / 0.26 / 0.21 | 0 / 0.08 / 0 | aaa_particles, Flywheel, Sable | R1 (aaa_particles fix) / R1 rejected. |
| `Iterators$1.hasNext` | 0.23 | 0.09 | micimmptl `ContraptionPortalImages.render` 0.11, `SubLevelPortalChains` | User's own mod; < 0.15%. Rejected. |
| `AccessoriesCapabilityImpl.getHolder`, `EntitySlotLoader.getEntitySlots` | 0.21 / 0.13 | 0.17 / 0.17 | `LivingEntity.accessoriesCapability()` from `getItemBySlot` cosmetic hook and `AccessoriesCapability.get` | Rejected: the slot map is a data-pack-reloadable `ImmutableMap` per entity type and the holder is per-entity attachment data. A safe cache needs reload hooks inside Accessories for about 0.2%. |
| `skinlayers CustomizableModelPart.compile` | 0.20 | 0.16 | 3D skin mesh | R1 config note (`renderDistanceLOD`). |
| `SectionCollector.visit` | 0.20 | 0.24 | Sodium occlusion graph | Real work. |
| `Level.getProfiler`, `Level.getGameTime`, `PoseStack.pushPose`, `Level.getHeight`, `LevelRenderer.getLightColor`, `RenderSystem._setShaderTexture` | 0.19 / 0.16 / 0.16 / 0.17 / 0.15 / 0.15 | 0.16 / 0.13 / 0 / 0.09 / 0.03 / 0.09 | trivial getters (cache-miss / safepoint bias), spread over many callers | Diffuse. Rejected. |
| veil `setDefaultUniforms` TAIL handler, `SegmentedBufferBuilder.getAllocatedSize`, Iris `sortEntityList` | 0.18 / 0.18 / 0 | 0.20 / 0.03 / 0.17 | Veil/Iris entity batching | Iris area. Rejected here. |
| `flerovium renderQuadList` | 0.45 | 0.10 | hotbar/held item quads (Flerovium is already the fast path) | Real work. |
| `String.valueOf` (B), `Throwable.fillInStackTrace` (B) | 0.13 / 0 | 0.25 / 0.19 | Curios `itemCacheKey` component `toString` (R1); Iceberg's `HumanoidArmorLayerMixin` `Class.forName` of two azurelib classes missing in 3.1.14 (`ClassNotFoundException` on every armor render, 0.53% in B) | Both gone in A: R1 Curios key fix; Iceberg's mixin is disabled by the user's micimmptl (`IcebergArmorLookupMixinPlugin` + `HumanoidArmorLayerBufferSourceMixin`). |
| **Relics `EntityUtils.lambda$findEquippedCurio`** (+ `UnmodifiableCollection$1.hasNext` B) | 0.03 | 0.22 (+0.10) | Relics air-collision hook under Shoulder Surfing camera ray | **Taken (#3)**. |
| Epic Fight `Mesh$DrawingFunction`, `SkinnedMesh.getVertexPosition/Normal` (B only) | 0 | 0.48 / 0.48 / 0.19 | CPU skinning on 2026-10-03 | Gone in A (compute skinning on). Config. |

### Inclusive-only subtrees (>= 0.5% inclusive) and specific asks

| Subtree | A incl | B incl | Finding | Decision |
|---|---|---|---|---|
| IP portal rendering (`IrisCompatibilityPortalRenderer.*`, `renderPortalContent`) | 12.3 | 34.3 | B is dominated by the 15.2% sync query (R1) + world re-render per portal | R1 (query). Re-rendering is needed (R1 rejected). |
| `EndSeaShadowRenderer.renderShadowMap` (Simulated) | 0.57 | 3.59 | | user's micimmptl `EndSeaShadowRendererOffMixin` now handles it; R1 rejected further work. |
| `ClientWorldLoader.tick`, `PacketRedirectionClient` | 1.06 / 0.44 | | | R1 rejected. |
| `TextureManager.tick` -> `cycleAnimationFrames` | 0.94 | 0.28 | **Which atlases**: Sodium 0.8's `animate_only_visible_textures` hooks `SpriteContents$Ticker.tickAndUpload` for **every** atlas (blocks+items, particles via `TextureSheetParticleMixin`, GUI via `GuiGraphicsMixin`, entity-model sprites via `TextureAtlas.getSprite`). A sprite only uploads if something drew it, or asked the atlas for it, since the last tick. The remaining 0.47% frame uploads and 0.28% interpolated uploads are sprites that are visible (or were looked up). | Rejected: anything more would skip visible animation frames. Non-visible item/particle animations do **not** upload. |
| azurelib `TextureManager.getTexture` -> `replaceAnimatableTexture` | 0.19 | 0.13 | one `HashMap<ResourceLocation,Boolean>` probe per `SimpleTexture` lookup (render-type setup) | Rejected: one O(1) probe; < 0.13%. |
| `AttributeMap.getValue` / `AttributeSupplier` | 0.43 | | 0.19% IP collision (**taken #2**); rest is vanilla `getScale` for renderers/ticks (< 0.03% each) | Partly taken. |
| owo `DerivedComponentMap.get` / `PatchedDataComponentMap.get` | 0.38 | | see `Reference2ObjectArrayMap` row | Rejected. |
| Accessories `getSlotTypes` / `CosmeticArmorLookupTogglable.getAlternativeStack` | 0.62 | 0.28 | see rows above | Rejected. |
| Jade `WailaTickHandler.tickClient` | 0.65 | 0.19 | `shouldRequestData` 0.19 (fluid/item storage capability probes, including a one-time `FluidStorage.<clinit>`), `gatherComponents` 0.17, ray trace 0.09 | Rejected: the results decide which server requests are sent and what the tooltip shows. Caching across ticks can show stale data. R1 config levers (disable storage providers) remain. |
| presencefootsteps `SoundEngine.onFrame` | 0.15 | 0.07 | stream over nearby entities + step simulation | Below threshold. Rejected. |
| Voxy `KineticSnapshots.tick` (user's shipdepth build) | 0.30 | | rebake 0.09, sweep 0.09, capture 0.09 (Create BE renderers) | User's own code, spread out, real work. Rejected. |
| Shoulder Surfing camera (`ShoulderSurfingCamera.setup`, `ObjectPicker.clip`) | 0.09 | 0.78 | B: 0.45% of it is Relics' air-collision hook | Fixed through **#3**. The rest is a real ray clip. |
| Xaero minimap HUD (`HudRenderer.render` 1.70, element renderers 0.65, waypoints 0.33) | 1.95 | | FBO redraw limited to 20 FPS by XaeroPlus (R1); waypoint/element draws are real draws | R1. |
| `DebugScreenOverlay` | 0.22 | | only while F3 is open | Not gameplay. Rejected. |
| ImmediatelyFast (`hudBatching` 7.08 incl., `BatchableBufferSource.endBatch` 1.61) | 7.08 | 6.35 | wrappers around the whole HUD; time is in the HUD elements and draws | Nothing of IF's own. |
| `ParticleEngine.tick` / `render` | 0.70 / 1.31 | 0.45 / 1.43 | particle_core async tick; render dominated by draws and `getRenderBoundingBox` | R1 rejected. Re-checked, unchanged. |
| `EntityRenderDispatcher.shouldRender` | 0.83 | 0.71 | Aether cape parallel stream via `getRenderer` (R1), Sodium `isEntityVisible`, vanilla cull box | R1 Aether; rest is the cull test itself. |
| `LevelRenderer` Iris shadow terrain / `beginLevelRender` (B) | | 11.1 / 3.1 | portal passes x Iris | Iris area / R1 portals. |

## B-only (portal scene) observations

Hot in 2026-10-03 but not in 2026-10-11:

* sync occlusion-query stall (15.2%): R1.
* Simulated End-sea shadow pass (3.6%): user's micimmptl.
* Iceberg `ClassNotFoundException` per armor render (0.53%): user's micimmptl.
* Voxy-Sable depth shim FBO validation (0.35%): user's newer Voxy shim.
* Epic Fight CPU skinning (about 1.2%): compute skinning now on.
* Relics air collision under the Shoulder Surfing camera (0.6%): **#3**.

Nothing portal-specific that is new and unpatched remains above the thresholds.

## For the lead to test

* Mixins apply (log with `-Dflyaway.logMixins=true`): `GcMonitorBeanListCacheMixin -> qouteall.imm_ptl.core.miscellaneous.GcMonitor`,
  `CollisionHelperNoGlobalPortalBoxMixin -> qouteall.imm_ptl.core.collision.CollisionHelper`,
  `EntityUtilsEquippedCurioMemoMixin -> it.hurts.sskirillss.relics.utils.EntityUtils`,
  `ShaderProgramUniformMapMixin -> foundry.veil.impl.client.render.shader.program.ShaderProgramImpl`.
* Portals: walk through an IP portal (teleport and collision still work). If a world with global portals exists
  (world wrapping, dimension stack), check that crossing them still works; the patch is inactive there anyway.
* Relics: with a Glitchy Mantle equipped, check that walking on air and phasing work as before. With a Leafy
  Mantle, check walking on leaves. Equip and unequip while moving.
* Veil / Aeronautics: levitite blocks on ships and Sable sub-levels render as before. Run a shader reload (F3+T)
  and check that uniforms still update (`applyProgram` path).
