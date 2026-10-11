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
