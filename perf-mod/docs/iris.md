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
