# Iris / Sodium / GL state (area `iris`, `sodium`, `vanilla.iris`)

Profile: user profile `s4gidwMiAz` (render thread only, 92.6 s, 4 ms sampling). Percentages are of the render thread.
Pack: Iris 1.8.14-beta.1, Sodium 0.8.13, Solas 3.6 (Colorwheel patched), Immersive Portals (compat renderer: the whole
Iris pipeline runs again per portal), Veil, Colorwheel, Epic Fight compute skinning, Sable/Aeronautics, Voxy.

What the profile says about this area:

| item | % | notes |
|---|---|---|
| `GL30C.glBindFramebuffer` self | 8.39 | 7.40 via GlStateManager: RenderTarget.bindWrite 2.79 (ExtendedShader.clear 1.6, SodiumShader.resetState 0.37, ...), ExtendedShader.bindFramebuffer 2.25, ClearPass 0.42, Sodium redirectIrisProgram 0.40, Veil 0.31, composites 0.36 |
| `ExtendedShader.apply` incl | 4.74 | bindFramebuffer 2.26, updateUniforms 1.97 (CustomUniforms.push 1.11, samplers 0.41), glUseProgram 0.36 |
| `CustomUniforms.push` incl | 1.76 | ExtendedShader 1.17, SodiumShader 0.25, composites 0.24, Colorwheel 0.05 |
| `glUseProgram` self | 1.62 | ExtendedShader 0.36, vanilla ShaderInstance 0.31, Iris Program 0.15, Flywheel/Veil/Voxy/EF/Sodium rest |
| `FullyBufferedMultiBufferSource.endBatch` incl | 5.63 | Iris entity batches: main view, shadow pass, hand, each portal pass |
| `IrisRenderingPipeline.renderShadows` incl | 10.3 | renderPlayerEntity 2.75 (player model: skin layers, EF, NEA...), shadow terrain 2.3+1.1, Colorwheel 1.9, entity batch 0.86 |

Important discovery: Iris already ships a "redundant framebuffer bind" cache (`MixinGlStateManager_FramebufferBinding`), but
it compares the cached id with the *target enum* (`iris$drawFramebuffer == target`) instead of the framebuffer id, so it
never hits. A naive global fix of that cache is not safe in this pack (see rejected ideas) - which is probably why it is
effectively dead upstream.

## Patches

All mixins are client-only, `defaultRequire = 1`, and can be switched off in `config/flyaway_perf.properties`.

### 1. Custom-uniform upload dedup - `iris.CustomUniformsPushDedupMixin` + `iris.CachedUniformVersionMixin`

* Target: `net.irisshaders.iris.uniforms.custom.CustomUniforms#push(Object)` (HEAD, cancellable, replaces the loop),
  `net.irisshaders.iris.uniforms.custom.cached.CachedUniform#update()` (HEAD).
* Problem: `push(pass)` runs on every draw (ExtendedShader.apply, SodiumShader.setupState, every composite pass,
  Colorwheel programs) and calls `pushIfChanged` for every shader-pack custom uniform and every built-in input those
  expressions use (Solas: ~40 incl. 4x4 matrices). `CachedUniform.changed` is set by every `update()` and
  `markUnchanged()` is never called in Iris 1.8, so every uniform is re-uploaded with `glUniform*` on every draw.
  1.76% of the render thread.
* Fix: a CachedUniform's pushed value only changes in `update()` (once per `beginLevelRendering`, i.e. once per world pass
  including each Immersive Portals pass). Each uniform gets a version (global counter bumped in `update()`); per pass
  object (= one GL program) we keep the version last uploaded and skip the `glUniform` call if it is unchanged. GL
  program uniforms persist per program, and every custom location is exclusively owned by CustomUniforms
  (`ProgramUniforms.Builder.location()` refuses a second owner for a name or location), so nothing else overwrites the
  value in between. Every `push(pass)` caller binds the pass' program first (checked: ExtendedShader, SodiumShader,
  CompositeRenderer, FinalPassRenderer, shadow computes, Colorwheel `ClrwlProgram.bind`).
  Values and update order are untouched (`update()` itself is not changed, so Solas' stateful `smooth()` uniforms
  behave exactly as before). If `CachedUniformVersionMixin` is disabled the dedup mixin falls back to always pushing.
* Expected gain: ~1.2-1.5% of the render thread (the first draw of every program per pass still uploads).
* Risk: low. A program re-linked in place under the same pass object would lose its values; Iris never does that
  (programs are rebuilt with new objects on pipeline reload). Voxy reads CachedUniform values directly into its own UBO
  (unaffected).
* Disable: `mixin.iris.CustomUniformsPushDedupMixin=false`.

### 2. Lazy main-target rebind inside Iris entity batches

Mixins: `iris.IrisBatchFboScopeMixin`, `iris.ExtendedShaderLazyRebindMixin`, `vanilla.iris.GlStateManagerFboDeferMixin`
(helper `dev.flyaway.perf.iris.IrisFboDeferral`).

* Targets:
  * `FullyBufferedMultiBufferSource#endBatch()` and `#endBatchWithType(TransparencyType)` - `@WrapMethod` opening a scope
    (try/finally). Colorwheel's `@Redirect` inside `endBatchWithType` composes with it.
  * `ExtendedShader#clear()` - `@WrapOperation` around `RenderTarget.bindWrite(Z)V`.
  * `GlStateManager#_glBindFramebuffer(II)V` (HEAD, cancellable) + HEAD hooks on every GlStateManager entry point whose
    effect depends on the current framebuffer binding: `_drawElements`, `_clear`, `_glBlitFrameBuffer`, both
    `_readPixels`, `_glCopyTexSubImage2D`, `_glDrawPixels`, `_glFramebufferTexture2D`, `_glFramebufferRenderbuffer`,
    `glCheckFramebufferStatus`, `getBoundFramebuffer`, `_getInteger`, `_glDeleteFramebuffers`.
* Problem: in an Iris batch every draw is `apply()` -> bind Iris gbuffer FBO, draw, `clear()` -> bind the main target,
  then the next draw binds an Iris FBO again (very often the same one, e.g. all `entity_cutout*` render types use the
  `entities` program). On NVIDIA/Windows every framebuffer switch is expensive (8.39% self in glBindFramebuffer).
* Fix: inside the scope, `clear()` still calls `bindWrite(false)` - so all Java side effects run exactly as before
  (Iris' `isMainBound` tracking which decides shader overrides, Veil's main-target wrapper redirect, ...) - but the
  resulting `glBindFramebuffer(GL_FRAMEBUFFER, main)` is recorded as *pending* instead of executed. The pending bind is
  * dropped when the next `GL_FRAMEBUFFER` bind arrives first (it would overwrite it anyway); if that bind targets the
    FBO that is still physically bound (the normal case), it is skipped too;
  * executed before any of the GlStateManager operations listed above, before a READ/DRAW-only bind, and when the
    scope ends (finally).
  So every GlStateManager-based operation sees exactly the binding unpatched Iris produces. Outside the scope (Sable
  sub-levels, Epic Fight compute meshes, sky, particles, Sodium terrain) every hook is one static-field check.
* Expected gain: up to both binds of every batched draw: ExtendedShader.clear inside endBatch ~0.75% + the matching
  apply binds ~0.95% => ~1-1.7% of the render thread (main view + shadow + hand + portal passes).
* Risk: medium-low. The scope only contains RenderType setup/draw/clear of already-buffered geometry. The one assumption
  is that nothing inside it binds a framebuffer or draws with raw GL calls that bypass GlStateManager. All raw
  `glBindFramebuffer` users in the pack were checked (NightFall/aaa_particles Effekseer, Immersive Portals, micimmptl,
  Voxy, owo, Sodium, vss) - none of them run inside an Iris entity batch (Effekseer runs at level-last, Voxy/Sable in
  their own stages, Epic Fight compute during entity *buffering*, not in endBatch). Veil binds through GlStateManager.
  Nested scopes are counted; state is reset on outermost entry/exit.
* Disable: `mixin.iris.ExtendedShaderLazyRebindMixin=false` (keeps tracking, no deferral) or
  `mixin.vanilla.GlStateManagerFboDeferMixin=false`, or `mixin.iris.IrisBatchFboScopeMixin=false` (turns off patches 2+3).

### 3. glUseProgram dedup inside Iris entity batches - `vanilla.iris.GlStateManagerProgramDedupMixin`

* Target: `GlStateManager#_glUseProgram(I)V` (HEAD, cancellable).
* Problem: vanilla `ShaderInstance.apply` dedups `glUseProgram` via `lastProgramId`; Iris' `ExtendedShader.apply` calls
  it unconditionally on every draw (0.36% of the render thread).
* Fix: only while an Iris batch scope is open (same scope as patch 2), skip `glUseProgram(p)` when `p` is the program set
  by the last executed GlStateManager call in that scope. Tracker reset on scope entry/exit. Flywheel's RETURN tracker
  and Iris' own HEAD hook stay consistent because a skipped call changes no state.
* Expected gain: ~0.1-0.25%.
* Risk: low (same scope assumption as patch 2: no raw `glUseProgram` inside entity batches; Sodium/Voxy/Epic Fight raw
  `glUseProgram` calls all happen outside).
* Disable: `mixin.vanilla.GlStateManagerProgramDedupMixin=false`.

### Debug statistics

`iris.debugStats=true` in `config/flyaway_perf.properties` logs every 10 s: deferred / executed main rebinds, skipped
gbuffer rebinds, skipped `glUseProgram`, skipped / done custom-uniform pushes. Useful to confirm the patches are active in
the lead's test run (expect "skipped" >> "done" for uniforms and executed << deferred for rebinds).

## Things the lead must test

1. Mixins apply (log line "Fly Away performance patches loaded (N mixins active)" and no mixin errors) with the full pack:
   targets shared with Sable (`ExtendedShaderMixin`: shadows only), Colorwheel (`FullyBufferedMultiBufferSourceMixin`:
   redirect in `endBatchWithType`), Iris' own GlStateManager hooks, Flywheel `GlStateManagerMixin`.
2. Visuals unchanged with Solas: entities, armor/trims/glint, hand, translucent entities, entity shadows (player shadow),
   Epic Fight animated mobs, Create contraptions (Colorwheel), Aeronautics ships, portals (Immersive Portals passes),
   Effekseer particles (aaa_particles/NightFall), Veil post effects, entity outlines (glowing).
3. With `iris.debugStats=true`, check the counters move as expected; then compare frame time with
   `area.iris=false` + `mixin.vanilla.GlStateManagerFboDeferMixin=false` + `mixin.vanilla.GlStateManagerProgramDedupMixin=false`.
4. Shader reload (F3+R / Iris reload key) and resource reload: no stale uniforms (sky colour, biome tint fades, time of day).

## Rejected / not done (with reasons)

* **Global GlStateManager framebuffer cache** (i.e. fix Iris' broken cache): unsafe. 17 classes in the pack call
  `glBindFramebuffer` raw (Voxy x9, Immersive Portals x2, micimmptl, owo, Sodium, vss, aaa_particles, NightFall's
  Effekseer) and Effekseer native code can bind as well; any of them desyncs a global cache and the next "redundant"
  bind would be skipped onto the wrong FBO. Re-syncing with `glGetInteger` at frame points stalls threaded drivers.
  Patch 2 gets the benefit only inside a scope where Iris controls all GL state.
* **Lazy rebind for SodiumShader.resetState / redirectIrisProgram** (0.77%): between two Sodium layers arbitrary code
  runs (LevelRenderer hooks, Sable `drawRenderSources`, Voxy shims with raw binds, NeoForge stage events). No safe scope.
* **Lazy rebind for Sable sub-levels / Epic Fight compute meshes** (~0.5% + ~0.3% of clear binds): the gaps between
  their draws contain raw GL (Voxy `VoxySableDepthShim` raw binds, Epic Fight raw `glUseProgram`/compute dispatch).
  Belongs to those areas if at all.
* **Texture-unit bind dedup** (`ExtendedShader.setupTextures` -> `glBindTextureUnit` 0.33% self): textures are bound by
  vanilla `GlStateManager._bindTexture` (tracked), Iris DSA `glBindTextureUnit` (untracked) and many raw callers; a
  cache cannot be kept exact.
* **Matrix/uniform dedup in `ExtendedShader.updateMatrices` / vanilla `Uniform.upload`** (~0.4% total for
  `glUniformMatrix*`): vanilla `Uniform.upload()` ignores its dirty flag, but `setDefaultUniforms` re-sets every value
  on every draw anyway, and Veil's `setDefaultUniforms` TAIL hook overwrites Iris' `NormalMat` every draw, so a
  value-compare guard would mostly miss; a global dirty-only upload is unsafe with mods that write vanilla uniform
  locations with raw `glUniform*`. Not worth the risk for <0.3%.
* **CustomUniforms.update per portal pass** (0.47%): values legitimately depend on the pass camera, and Solas uses
  stateful `smooth()` functions whose progression depends on how often `update()` runs. Changing it would change
  visuals.
* **ClearPass** (0.42% bind + 0.30% clear): each clear pass targets a different FBO; the binds are real state changes.
  A DSA `glClearNamedFramebuffer` rewrite would have to replicate scissor/colour-mask/draw-buffer semantics - not worth it.
* **Shadow pass player rendering** (2.75%): the cost is the player model itself (3D skin layers, Epic Fight patched
  renderer, Not Enough Animations, armor, held items), rendered because Solas sets `shadowPlayer=true`. Culling is
  already applied (`dispatcher.shouldRender` with the shadow frustum). Skipping/caching it would change the shadow.
  Entity shadows (`shadowEntities`) and block-entity shadows are already off in Solas' default profile.

## Config-only recommendations (not applied - configs are owned by another agent)

* `config/iris.properties` `maxShadowRenderDistance=32`: **has no effect with Solas** - Solas sets
  `shadow.culling=reversed`, for which `ShadowRenderer.createShadowFrustum` uses the pack's `const float voxelDistance`
  (Solas derives it from its voxel volume, `VOXEL_VOLUME_SIZE` option) as the "safe zone" cull distance and never reads
  the user slider. The CPU cost of the shadow terrain pass (Sodium shadow layers ~3.4% incl.
  Sable sub-levels) is driven by Solas' `shadowDistance` / `VOXEL_VOLUME_SIZE` options (in-game shader options,
  saved to `shaderpacks/Solas Shader V3.6 - Colorwheel Patched.zip.txt`). Lowering `shadowDistance` (e.g. 192 -> 128)
  is a visible trade-off (shadow reach), so only as an optional "performance" preset.
* `colorSpace=DISPLAY_P3`: Iris runs a colour-space conversion pass every frame (`ColorSpaceFragmentConverter`, one
  extra fullscreen pass + FBO bind). `SRGB` removes it but changes colours on P3 displays - only recommend if the
  user's monitor is sRGB anyway (then it is also more correct).
* Solas `SHADOW_ENTITIES` must stay off (default profile): turning it on would add every entity + block entity to the
  shadow pass (the player alone costs 2.75%).
* `sodium-options.json`: already good for this workload (`use_no_error_g_l_context=true`, `cpu_render_ahead_limit=3`,
  `chunk_build_defer_mode=ALWAYS`, entity culling + fog occlusion on). Nothing to change.
* Immersive Portals: every visible portal re-runs the whole Iris pipeline (begin, shadows, composites, final) -
  limiting the number of rendered portals / portal render distance in `immersive_portals.json` is the biggest
  config lever for this area (owned by the imm_ptl agent).

## Round 2 - remaining per-draw CPU in Iris programs

I re-read the profile with a merged call tree, where every occurrence of a method is merged by callee. After round 1,
`ExtendedShader.apply` (4.74%) breaks down like this:

| part | % | status |
|---|---|---|
| framebuffer bind | 2.26 | round 1 |
| `CustomUniforms.push` | 1.17 | round 1 |
| vanilla `Uniform.upload` | 0.48 | patch 4 |
| `glUseProgram` | 0.37 | round 1 |
| `ProgramSamplers.update` (sampler objects + `glBindTextureUnit`) | 0.18 | rejected |
| `ProgramUniforms.update` | 0.09 | - |
| `updateMatrices` | 0.06 | - |
| `setupTextures` | 0.03 | - |

Before each of these draws, `ShaderInstance.setDefaultUniforms` costs 1.18%. That splits into the sampler `HashMap.put`
(0.47), Veil's TAIL hook (0.29), Veil's uniform wrappers (0.17) and `Uniform.markDirty` (0.14).

`ExtendedShader.clear` costs 1.85%. Most of it is the main-target rebind (1.51, round 1). The rest is the sampler
unbind `glBindSamplers` (0.22).

### 4. Vanilla uniform upload dedup for Iris programs - `iris.ExtendedShaderUniformDedupMixin` + `vanilla.iris.UniformUploadDedupMixin` (+ `iris.ProgramUniformsBuilderAccessor`)

* Targets:
  * `ExtendedShader#<init>`: `@WrapOperation` around `ProgramUniforms$Builder.buildUniforms()`.
  * `ExtendedShader#uploadIfNotNull(Uniform)`: `@WrapOperation` around `Uniform.upload()`.
  * Vanilla `com.mojang.blaze3d.shaders.Uniform#upload()` and `#setLocation(I)`: HEAD hooks that only clear a flag.
* Problem: `updateUniforms()` uploads every vanilla uniform of the program with `glUniform*` on every draw. These are
  `iris_ModelViewMat`, `iris_ProjMat`, `iris_ModelViewMatInverse`, `iris_ProjMatInverse`, `iris_NormalMat`,
  `iris_ColorModulator`, `iris_Fog*`, `iris_TextureMat`, `iris_ChunkOffset`, `iris_GlintAlpha`, `iris_OverlayUV` and
  `iris_LightUV`.
  * Vanilla `Uniform.upload()` ignores its dirty flag.
  * The inverse and normal matrices are uploaded twice per draw: once explicitly and once more from the list.
  * Inside a batch, nearly all values are the same from one draw to the next.

  Cost: 0.48% of the render thread.
* Fix: `uploadIfNotNull` is only reached from `updateUniforms`, right after `apply()` made the program current. There,
  the mixin compares the uniform's current value bit-for-bit with the value last uploaded to this program. If they
  are equal, it skips the GL call. Program uniform state persists per program, so the program still holds that value
  as long as nothing wrote the location in between. These rules make sure of that:
  * Any other `Uniform.upload()` call clears the stored value. Examples are Sable's `sable$refreshModelMatrices` and
    Epic Fight. `glUniform*` writes to whatever program is bound, so the patch does not try to track those writes.
  * `Uniform.setLocation()` clears the stored value. Veil's shader_recompile swaps or relinks a ShaderInstance's program
    and always re-resolves the locations (`veil$invalidate`).
  * Locations that Iris writes itself are never deduplicated. At construction the patch records every location that
    the program's `ProgramUniforms.Builder` handed out: Iris built-ins, shader-pack custom uniforms and API uniforms.
    This matters because `iris_FogColor`, `iris_FogStart` and `iris_FogEnd` are both vanilla uniforms and Iris
    PER_FRAME uniforms.
  * Only uniforms that this shader created are handled (`Uniform.parent == this`). Iris' shared `FAKE_UNIFORM` is not.

  Values that do change are uploaded as usual: per draw, per Immersive Portals pass, and between shadow and main
  programs. I grepped all mod sources for the `iris_*` vanilla uniform names, and no mod writes them with raw
  `glUniform*`.
* Expected gain: about 0.3-0.4% of the render thread. That is the 0.48% minus the first upload of each changed value
  and minus the cost of the comparison.
* Risk: low.
* Disable: `mixin.iris.ExtendedShaderUniformDedupMixin=false`. Alternatively `mixin.vanilla.UniformUploadDedupMixin=false`:
  without the vanilla mixin, the Iris side falls back to a plain `upload()`.
* Debug: with `iris.debugStats=true`, the log also shows "ExtendedShader vanilla uniform uploads skipped / done".

### 5. Constant sampler names in `ShaderInstance.setDefaultUniforms` - `vanilla.iris.ShaderInstanceSamplerNamesMixin`

* Target: `ShaderInstance#setDefaultUniforms`. A `@ModifyConstant` changes the loop bound `12` to `0`; it is the only
  `bipush 12` in the method. A HEAD `@Inject` then runs the same loop.
* Problem: the method runs before every draw that goes through `VertexBuffer.drawWithShader` or `BufferUploader`. That
  covers all Iris entity batches, Sable sub-levels and Epic Fight meshes. It calls
  `setSampler("Sampler" + i, getShaderTexture(i))` 12 times, which creates a new String each time. `HashMap.put` then
  has to compute the hash and compare the characters of each one. Cost: `HashMap.put` 0.47% (putVal 0.30, hash 0.12).
* Fix: the same calls run in the same order, but with interned constant names. Their hash is cached and nothing is
  allocated. The virtual `setSampler` call is kept, because Veil's `ShaderProgramImpl.Wrapper` overrides it. Two other
  mods' mixins hook this method and are not affected: Veil at TAIL, and Sodium Extra's `@ModifyExpressionValue` on the
  fog getters.
* Expected gain: about 0.2-0.3%.
* Risk: very low.
* Disable: `mixin.vanilla.ShaderInstanceSamplerNamesMixin=false`.

### 6. Empty-builder fast path in Iris batched rendering - `iris.SegmentedBufferBuilderEmptyMixin`

* Target: `SegmentedBufferBuilder#getSegments()` (HEAD, cancellable).
* Problem: `FullyBufferedMultiBufferSource.readyUp()` calls `getSegments()` on all 32 builders for every batch: main,
  shadow, hand and each portal pass. Each call allocates a capturing lambda and copies an `ArrayList`. That happens even
  for the many builders that received no geometry in this batch. Cost: `ArrayList.<init>` under `getSegments`, 0.22%
  self.
* Fix: when the builder holds no `BufferBuilder` and no pending segment, return `Collections.emptyList()`. The only
  caller just iterates over the result, so this is exactly equivalent.
* Expected gain: about 0.1-0.2%.
* Risk: none.
* Disable: `mixin.iris.SegmentedBufferBuilderEmptyMixin=false`.

### Round 2: things the lead must test

1. Check that these mixins apply:
   * the `@WrapOperation` in the `ExtendedShader` constructor (MixinExtras in a constructor, after `super()`);
   * the `uploadIfNotNull` wrap (Sable's `ExtendedShaderMixin` on the same class only adds a method);
   * the `Uniform` mixin;
   * the constant/HEAD pair on `ShaderInstance.setDefaultUniforms` (Veil and Sodium Extra mix into the same method).
2. Compare visuals with Solas:
   * fog colour and distance transitions: underwater, lava, blindness, nether;
   * enchantment glint on items and armor (`iris_TextureMat` changes per glint draw);
   * entities in Create contraptions and Aeronautics sub-levels (Sable refreshes the matrices);
   * Epic Fight mobs;
   * hand rendering;
   * portals (each pass uses a different camera);
   * shader reload and resource reload (new programs start with no stored values);
   * F3+T.
3. With `iris.debugStats=true`, "ExtendedShader vanilla uniform uploads skipped" should be several times "done".

### Round 2: rejected / not done

* **Deferring the sampler-object unbind** (`ExtendedShader.clear` -> `IrisRenderSystem.unbindAllSamplers` ->
  `glBindSamplers(0, all)`, 0.22%, followed by a rebind on the next draw). If it were deferred like the FBO bind, Iris
  sampler objects would stay bound across non-Iris draws in the batch. Mod shaders that use `Sampler3+` on the same
  units would then sample with Iris' filtering and wrap settings. Making it exact would need tracking, on every draw
  entry point, of whether the current program is an Iris program that rebound its samplers. Vanilla
  `ShaderInstance.apply` can also skip `glUseProgram` through `lastProgramId`. Too fragile for 0.2%.
* **Caching `AbstractTexture.setFilter`** (the TextureStateShard setup calls Veil's DSA `glTextureParameteri` twice per
  draw, 0.27%). `NativeImage.upload` sets the MIN/MAG filter with a raw `GlStateManager._texParameter` on the bound
  texture: every tick for atlas animation uploads, and for dynamic textures. Iris PBR, mipmap code and other mods also
  write texture parameters directly. A per-texture "last filter" cache cannot be kept exact, and a wrong filter is
  visible as blurry or pixelated textures.
* **Deduplicating `glGenerateTextureMipmap`** (0.43%). With Solas' default options, mipmaps are generated before
  composite3 (colortex0), composite13 (colortex0) and composite15 (colortex1). Composite3 and composite14 rewrite those
  textures first, so none of the three is redundant. Only with `DOF` enabled would composite14's colortex0 mipmap repeat
  composite13's. Proving that nothing wrote the texture in between would also mean tracking compute image stores.
* **Iris sampler and texture-unit rebinds** (`SamplerBinding.updateSampler` 0.18%, Iris `TextureTracker` unit-0 bind
  0.15%). Same reason as in round 1: texture units are shared with raw GL users.
* **Uniform dedup for vanilla (non-Iris) `ShaderInstance.apply`** (0.16%). Vanilla programs are shared with mods that
  write uniform locations themselves (Veil wrappers, the Immersive Portals clipping uniform, ...).
* **Veil's `setDefaultUniforms` TAIL hook** (0.29%): 8 `getUniform` lookups per draw, and on ExtendedShader each one
  builds an `"iris_" + name` string. This is Veil's code and most of the gain would come from the lookups. It belongs
  in a compat patch if anyone wants it.
* **`FullyBufferedMultiBufferSource.getTargetClearTime` -> `getAllocatedSize`** (0.22%). Once per batch it adds up the
  capacity of every buffer, only to pick a buffer-retention time from 1 GiB / 5 GiB thresholds. It would be cheap to
  cache. However, the spark Java sampler is safepoint-biased and the loop is tiny, so the real cost is uncertain.
* **Composite passes** (`CompositeRenderer.renderAll` 2.46%). What is left is real work: full-screen draws 0.48,
  mipmaps 0.43, compute dispatches, custom uniforms (round 1) and per-pass FBO binds 0.37 (every pass targets a
  different FBO).
* **ClearPass / beginLevelRendering**: these are real binds and clears per target. For `CustomUniforms.update`, see
  round 1.


## Review round 2 note
The vanilla-uniform dedup (round 2) assumes the ExtendedShader program is really bound when `updateUniforms` runs.
If some mod issued a raw `glUseProgram` inside an Iris batch scope, round 1's `glUseProgram` dedup could skip a needed
rebind, and the uniform dedup would then keep a mis-routed constant (ColorModulator / Fog*) until its value changes.
No such raw call was found in the pack (Epic Fight compute, NightFall RenderState and Veil all leave state consistent),
but if fog/tint looks wrong on some entities, try `mixin.vanilla.GlStateManagerProgramDedupMixin=false` first.
