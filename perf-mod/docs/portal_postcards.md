# Portal postcards: a portal seen through a portal, under Iris

Mixin: `imm_ptl.IrisCompatPostcardMixin` (client, `flyaway_perf.imm_ptl.mixins.json`). Code:
`dev.flyaway.perf.imm_ptl.postcard.*`. Shaders: `assets/flyaway_perf/shaders/postcard_{capture,display}.{vsh,fsh}`.

This is a visible FEATURE, not a perf patch. It only runs on the Iris path of Immersive Portals. Without a shader
pack, IP renders real nested portal layers and none of this code runs.

## The problem

With Iris shaders active, Immersive Portals 6.0.7 uses `IrisCompatibilityPortalRenderer`. micimmptl forces IP's
compatibility render mode because the stencil renderer corrupts Voxy. This renderer draws exactly one portal layer:

* `onBeforeHandRendering` runs portal rendering only when `!PortalRendering.isRendering()`.
* `doRenderPortal` returns at once while a portal pass is running.

A portal Q that is seen through another portal P is therefore never processed. In P's view, Q's frame is empty, so
it looks like an inactive portal. A real second layer would need a second full Iris pipeline pass (shadow,
gbuffers, composites) for every nested portal. That is far too expensive.

## Current default: "paint" style (round 6)

After the second play test the reprojected snapshot still looked off: a snapshot taken from one viewpoint never
convincingly passes for a live view. So the default no longer tries to imitate the destination at all:
`imm_ptl.postcards.style=paint` (default) draws a stylised, animated surface instead.

* Look: Balatro's background paint shader. It is a polar spin, a 5-step sin/cos domain warp and a 3-colour paint
  ramp with highlights. It is reimplemented from the community ports (credits: LocalThunk's Balatro; ports by
  xxidbr9, [godotshaders](https://godotshaders.com/shader/balatro-background-shader/), Shadertoy `XXtBRr`, which is
  CC BY-NC-SA). Fine for a private modpack; do not ship it commercially.
* Parallax: the paint is evaluated on a virtual plane `0.8 * max(half width, half height)` behind the portal, along
  the view ray. It slides against the frame as you move, so the portal reads as a hole with depth, not a sticker.
  The angle term is clamped at 0.25 so grazing views don't stretch to infinity.
* Pixelated to 16 texels per block on that plane, to match Minecraft textures. Each portal gets its own phase
  (UUID hash). The rim fades into the gap colour.
* Palettes (c1 main, c2 second, c3 gaps), in `PortalPalette`:

| destination | c1 | c2 | c3 |
|---|---|---|---|
| overworld | sky `4F9BE8` | grass `5DB33A` | night `0E1A26` |
| the_nether | lava `E8541C` | crimson `8E1328` | basalt `1A0507` |
| the_end | chorus `A35BD6` | end stone `E6E39A` | void `07040D` |
| aether:the_aether | sky `8CCBFF` | glowstone gold `F2C94C` | deep sky `31507A` |
| anything else | the destination fog colour's hue (saturation at least 0.55, value at least 0.75) | the hue rotated by 151 degrees, value 0.65 | the hue at value 0.1 |

  For "anything else", the destination fog colour is read when that client world is loaded. Otherwise the hue comes
  from a hash of the dimension id. Override any dimension with
  `imm_ptl.postcards.palette.<namespace>.<path>=RRGGBB,RRGGBB,RRGGBB`.
* No captures run in this style: no direct captures, no main-view captures, and no postcard textures. Cost per
  nested portal: about 15 uniforms and one draw, plus about 40 ALU-heavy ops per covered pixel.
* The fade from a nested portal to the live view (walking through the outer portal) still runs, with the paint
  surface fading out.
* `imm_ptl.postcards.style=snapshot` brings back everything below.

## Rework after the first real play test

What the user saw: in normal play the nested portal was mostly the purple swirl, with a sheared, partial fragment of
the world in one corner. It only looked right after leaving the dimension while looking straight at the portal.

Two causes:

1. **No postcard in the common case.** A postcard was captured only in a pass through the portal itself, so only after
   the player had looked at that exact portal. A portal seen only nested, or the portal you walked out of and turned
   your back on, never got one. That left the swirl.
2. **Tearing parallax.** Reprojection marched against the full-resolution captured depth. From a viewpoint that differs
   from the capture's, every depth edge (a tree in front of the sky, a block edge) opens a gap the capture never saw.
   The ray then jumps from the foreground to the background, which reads as shearing. Rays that left the captured
   rectangle faded into the swirl within 10%, which gave the hard seams.

Fixes (sections 1b, 2 and 3 below have the details):

* **Captures from the player's own view (1b).** A portal Q shows "the destination world, seen from Q's destination
  looking along Q's content direction". When the player stands near Q's destination and looks that way, the main view
  is almost exactly that. This happens right after walking out of Q, and when walking away from the portal you came
  through. So the main view is captured as Q's postcard. Portals in any loaded world whose destination is within 48
  blocks are candidates. A direct capture still wins: score 1 against at most 0.9.
* **Smooth depth proxy (2).** The capture stores disparity (1/distance) with a full mip chain. The march uses a coarse
  level, about 96 px across. A smooth surface cannot tear: near objects get a soft, slightly wrong parallax instead of
  gaps.
* **Blurred continuation instead of the swirl (2).** A ray outside the captured area samples the nearest captured edge
  from an increasingly blurred mip level. It only fades towards the membrane far outside.
* **Membrane instead of the swirl (3).** A portal with no capture at all shows a calm gradient in the destination's fog
  colour, with slow low-contrast motion. It is only 15% portal purple, down from 35%.
* Offscreen GPU test (same harness as below, with 7 camera positions including two 35-40 degree oblique ones, plus a
  "main-view" capture taken 0.6 blocks in front of Q's destination): pixels within 8% of ground truth.

| | old (direct capture) | new (direct capture) | new (main-view capture) |
|---|---|---|---|
| identity camera | 88.8% | 98.0% | 81% |
| moved 0.6 blocks, turned 8 degrees | about 89% | 93.9% | 84.5% |
| oblique, 40 degrees | not measured (tears) | 59% (soft smear, no gaps) | 71% |

## Design

### 1. Capture (end of every layer-1 portal pass)

Hook: `IrisCompatibilityPortalRenderer.onBeforeHandRendering` HEAD, only when `PortalRendering.isRendering()` and
`getPortalLayer() == 1`.

* This call happens inside P's pass, right after `LevelRenderer.renderLevel` returns. By then Iris has run its
  composites and its final pass (`iris$endLevelRender` at `renderLevel` RETURN) into the main render target.
* The main target therefore holds P's finished view in colour, plus Iris's depth (Iris uses the main target's
  depth texture as `depthtex0`).
* This is also the exact place where IP would recurse into layer 2 without Iris.

Steps:

* Project P's 4 destination-side corners (`transformPoint(getPointInPlane(+-w/2, +-h/2))`) with the pass camera
  (`RenderSystem.getProjectionMatrix() * modelView`, camera `CHelper.getCurrentCameraPos()`). This gives P's screen
  rectangle. If a corner is behind the camera, the whole screen is used.
* One fullscreen-triangle draw copies that rectangle into the portal's postcard:
  * the colour is box-filtered with 4 bilinear taps into an `RGBA8` texture;
  * the raw window depth (nearest sample) goes into an `R32F` texture.
  * The postcard is `resolution x resolution`, and only the part that matches the rectangle's aspect ratio is used.
* Stored with the postcard:
  * the capture `projection * view` (relative to the capture camera);
  * the capture camera position, in double precision;
  * projection `m22` and `m32`, used to linearise depth;
  * the captured NDC rectangle;
  * the portal's destination.
* Refresh: at most every `imm_ptl.postcards.refreshFrames` frames per portal (default 4).
* Completeness: the capture stores the fraction of the portal's screen rectangle that was on screen (`coverage`). A
  capture that is less complete than the stored one (portal partly off-screen) does not replace it for 20 s. Without
  this, a glance that put the portal at the screen edge replaced a full postcard with a sliver, and the nested
  portal then showed mostly the swirl fallback (this happened in the first real-client test).
* Why a draw and not `glBlitFramebuffer`:
  * The brief suggested blit or `glCopyImageSubData`. A depth blit needs identical depth formats on both sides, and
    the main target's depth format depends on IP's stencil toggling and on the driver. It can also only copy
    1:1 or with nearest scaling.
  * One small draw that samples the colour and depth textures is still GPU-only (no readback). It is
    format-agnostic and downsamples in the same step.

Key and lifetime:

* Postcards are keyed by portal UUID. The destination dimension is checked on use: a postcard captured for another
  destination is ignored, and so is one whose portal destination has moved by more than 0.5 blocks.
* Eviction:
  * LRU once the memory cap is reached (`imm_ptl.postcards.maxMemoryMB`, default 64; about 6.3 MB per postcard at 768).
  * The postcard has not been used for 120 s.
  * The portal entity was removed and has not been seen again for 10 s.
  * The client level the portal lives in unloads (`LevelEvent.Unload`).
  * Resource reload, or logout.
  * Iris shader reload. This is detected through `PipelineManager` identity and
    `getVersionCounterForSodiumShaderReload`.

### 1b. Main-view captures (the player's own view)

Hooks:

* `GameRendererPostcardMainViewMixin`: wraps `LevelRenderer.renderLevel` in `GameRenderer.renderLevel`. It records the
  main view's `modelView` and projection, then calls `onMainViewFinished` right after the world render.
* `IrisCompatPostcardMixin`: in `onBeforeHandRendering`, before `IPIrisHelper.newCopyDepthStencil`, which is the
  start of the main-pass portal rendering.

IP wraps the same call and runs its portal passes after it. The passes overwrite the main target's depth, so the
capture must happen first. Whichever of the two hooks comes first in a frame without a portal pass does the work. A
frame stamp (`lastPassFrame`) skips the late hook if passes already ran. micimmptl's idle skip cancels the second hook
when no portal is within 128 blocks; then the first hook runs, and the depth is still clean because no pass ran.

Steps:

* Candidates: every 20 frames, the portals in every loaded client world whose destination is this world and within
  48 blocks of the camera. Global and fuse-view portals are skipped.
* Score (0 means skip). The camera must be at most 0.3 blocks behind Q's destination plane, on the content side. The
  look direction must be within about 63 degrees of Q's content direction (dot >= 0.45). The score is
  `0.9 * smoothstep(facing) / (1 + 0.05 * (depth beyond the plane - 1) + 0.25 * (lateral offset beyond Q's half size))`,
  and anything below 0.15 counts as 0.
* At most one capture per frame (the best due candidate). Each portal is captured at most every
  `2 * refreshFrames` frames, and never during a fade. The capture uses the whole screen.
* Replacement: a capture replaces the stored one only if its score is at least the stored score minus 0.05, or the
  stored one is more than 20 s old. Direct captures (pass through Q, score = on-screen coverage) use the same rule.
* Masking: anything closer than 0.6 blocks (hand and held item) is stored as invalid. With a detached camera (F5,
  Shoulder Surfing), the player's body is also stored as invalid: a capsule of radius 0.85 from feet to head,
  reconstructed with the inverse view-projection. Invalid texels are filled from coarser mip levels on display
  (push-pull).
* A main-view capture never evicts another postcard. It only runs if the memory cap has room, and it does not count as
  "use" for the LRU.
* It only runs when IP's active renderer is `IrisCompatibilityPortalRenderer`, so not without shaders.

### 2. Display (nested portals, same hook, right after the capture)

* Nested portals come from IP's own `getPortalsToRender(modelView)`, called in the pass (client.level is the
  destination world there). It applies the same visibility, frustum and range filters, `cannotRenderInMe` (the
  outer portal's reverse) and `PortalRenderingPredicateEvent` that IP uses for real nested layers.
* Global (world-wrapping) portals and fuse-view portals are skipped. Nothing is drawn if IP's configured `maxPortalLayer < 2`. The gate reads `IPGlobal.maxPortalLayer`, not
  `PortalRendering.getMaxPortalLayer()`: the latter returns 1 while IP's lag detector (`RenderStates.isLaggy`) is on,
  but a postcard costs one textured quad, not a world pass.
* Each nested portal Q is drawn into the main target with `Portal.renderViewAreaMesh`. This is the same mesh IP's
  `ViewAreaRenderer` uses, so special shapes work.
* Draw state:
  * depth test `LEQUAL` against the pass depth, so terrain and entities in front of Q occlude it;
  * no depth write;
  * alpha blending;
  * alpha channel masked;
  * depth clamp.
* The fragment shader discards everything on the near side of the outer portal's destination plane, which is P's
  clip plane.
* IP then copies P's area of the main target into its deferred buffer (`drawPortalAreaWithFramebuffer`). The
  postcard therefore ends up inside P, and only inside P, without any extra clipping work.
* Why not inside the Iris pipeline: that would need an Iris `ExtendedShader` program. The shader pack would light
  and fog an image that is already lit. It would also depend on gbuffer layouts that differ per pack. Drawing after
  the pass's final composite, with a depth test against the pass depth, needs no Iris interaction. It also works the
  same with every pack.

Reprojection (`postcard_display.fsh`). The description below is the original design. Since the rework, these
details differ:

* The march (20 steps from 5 cm to about 380 blocks, then 6 bisection steps) compares disparities against mip level
  `log2(resolution / 96)` of the disparity texture. That is a smooth proxy, so there is no disocclusion branch.
* Steps behind the capture camera, and steps outside its frustum before the ray has entered it, are skipped. A
  main-view capture is taken in front of Q's destination plane, so the start of every ray is behind it.
* Leaving the captured area samples the clamped edge at mip level `1.5 + 6 * distance outside`. Confidence falls to
  20% at 0.6 rectangle widths outside.
* The colour is stored premultiplied, with alpha marking valid texels. Invalid texels are filled from mip levels +3
  and +6.

Original description:

* Take the fragment's camera-relative position X on Q.
* Carry the ray (camera to X) through Q's portal transform into the capture's space, using:
  * the linear part `L` (rotation, scale, mirror), built from `transformLocalVec` of the unit axes;
  * the offset `Q.transformPoint(camera) - captureCamera`, computed in doubles.
* March the ray: 24 exponentially spaced steps from 4 cm out to about 900 blocks.
  * For each step, project the point with the capture `projection * view`.
  * Compare its view distance (`clip.w`) with the stored depth, linearised as `m32 / (ndc_z + m22)`.
* First crossing: refine with 6 bisection steps, then sample the colour.
* Ray passed behind an occluder (disocclusion, distance gap > 0.6 + 8%): use a 4-tap blur, at confidence 0.9.
* No crossing:
  * The ray goes to the sky: sample the point at infinity (`VP * vec4(dir, 0)`).
  * The ray leaves the captured rectangle: estimate where it would meet the last known depth. Fade into the swirl
    over a 10% band outside the rectangle. This avoids streaks.
* Offscreen GPU test (llvmpipe, a synthetic scene, our real shader files; ground truth rendered from the moved
  camera):

| camera (relative to capture) | Q pixels within 8% of ground truth |
|---|---|
| identity | 88.8% (the misses are the vignette border and checker edges after downsampling) |
| moved 0.6 blocks and turned 8 degrees | about 89% |
| moved close to Q and turned 12 degrees | 62% (the uncovered part becomes swirl, as designed) |

### 3. Fallback membrane (was: swirl)

* There is no postcard until the portal has been seen directly at least once, or the player has looked out from
  near its destination (1b).
* Since the rework, the fallback is a calm vertical gradient of the destination tint with slow low-contrast motion
  and a faint purple shimmer. The tint is the destination fog colour mixed 15% (was 35%) toward portal purple. The
  original swirl is described below.
* Until then, a cheap animated swirl is drawn. It uses polar sine bands and a time uniform.
* It is tinted with the destination's fog colour, mixed 35% toward portal purple:
  * the biome fog colour at the destination, if that client world is loaded and has the chunk;
  * otherwise a per-dimension default.
* The tint is refreshed every 100 frames.

### 4. Fades

* Swirl to postcard: when a postcard first becomes valid for a nested portal, it fades in over
  `imm_ptl.postcards.fadeMs` (350 ms).
* Postcard to live view: hook `doRenderPortal` after `drawPortalAreaWithFramebuffer` (main pass, Q drawn live).
  * Trigger: Q was a postcard within the last 3 frames and was not live within the last 3 frames. Example: the
    player walked through P.
  * Effect: the postcard is drawn over the live view in IP's deferred buffer, with smoothstep-decreasing opacity
    for `fadeMs`. A depth bias of 1e-5 lets it pass against IP's own portal-area depth.
  * Captures of Q are held back while the fade is pending or running, so the outgoing image is the old postcard and
    not the fresh live frame.
* Live view to postcard (stepping back through) is not faded. The postcard is fresh at that moment because Q was
  just seen live, so the change is small.
* Edge vignette: the outer 28% of Q is darkened slightly and tinted toward the destination colour, so the surface
  reads as a portal.

## Cost

* CPU, layer-1 pass with no nested portal: one `getPortalsToRender` (an entity iteration over the destination world,
  as IP does each frame for the main view). In capture frames (every `refreshFrames`): a GL section of about 35
  `glGet*` state queries, about 25 GL calls and 1 draw.
* CPU, with nested portals: the same, plus per nested portal about 30 uniform calls, 1 buffer upload of a few
  triangles and 1 draw. A handful of `Vec3` temporaries come from IP's transform API. There is no other per-frame
  allocation: matrices, the mesh buffer and the state snapshot are preallocated.
* No readback: nothing is read from the GPU. The only `glGet*` calls are state queries, used to restore state
  exactly.
  * Why not GlStateManager's caches: they are private, and BufferUploader, ShaderInstance and Iris all keep their
    own "last bound" caches.
  * Restoring the queried values exactly keeps every one of those caches valid.
  * On drivers with a threaded GL front end (NVIDIA "threaded optimization"), each query is a client-side sync.
    That costs microseconds, and only in frames where a capture or a nested draw happens.
* GPU: the capture is one draw of at most `resolution^2` pixels (768^2 by default) with 4+1 texture fetches per
  pixel. The display is up to about 60 texture fetches per covered pixel of a nested portal, which is usually a
  small part of the screen.
* GPU memory: 8 bytes per pixel plus mip chains (RGBA8 + RG16F), about 6.3 MB per postcard at 768. Capped by
  `maxMemoryMB`.
* Main-view captures: one full-screen downsample draw plus two `glGenerateMipmap` calls, at most once per frame and
  per portal every `2 * refreshFrames` frames, only while a candidate scores. The candidate scan runs every 20 frames
  and iterates the entities of the loaded client worlds.

## Limits

* A postcard exists only after the portal has been seen directly, or after the player has looked out from near its
  destination (1b). A portal that has only ever been seen through another portal shows the membrane.
* The smooth depth proxy trades exact parallax for stability. Thin foreground objects (a pillar a few blocks from
  the capture camera) smear slightly and sit a little too far away when seen from a different angle.
* Postcards are snapshots. Animated content (mobs, water, particles) behind the nested portal is frozen until the
  player looks at that portal directly again.
* Reprojection only knows what the capture camera saw. Looking into Q from a very different angle reveals regions
  that were never captured: they get a blurred continuation of the nearest captured edge.
* The postcard has the shader pack's final look of the moment it was captured: lighting, time of day and
  post-processing. The outer pass's fog and post effects are not re-applied to it.
* One recursion level: postcards are captured before nested postcards are drawn, so a postcard never contains
  another postcard.
* Voxy LOD terrain that is not in the main depth buffer cannot occlude a nested postcard.
* Requires GLSL 330 core and sampler objects (GL 3.3). If shader compilation or FBO creation fails, the feature
  logs one error and switches itself off for the session.

## Coexistence

* Our patches:
  * `IrisFboDeferral`: a pending deferred bind is flushed before our GL section. We never run inside an Iris
    batch scope.
  * `GameRendererFrameScopeMixin` and `PortalRenderInfoNoSyncWhenHiddenMixin` are untouched.
* micimmptl:
  * `IrisCompatIdleSkipMixin` (HEAD cancel, main pass only) is independent.
  * `IrisCompatPortalScissorMixin`: the scissor it enables for the pass is saved, disabled during our section and
    restored. The nested draw needs no scissor, because IP only copies P's area.
  * `IrisCompatPortalViewReuseMixin` (off by default): when it reuses an image it cancels `doRenderPortal`. In that
    case there is no pass, so there is no capture and no fade, and nothing breaks.
  * Voxy in portals, Sable hull clipping and Simple Clouds per portal all run inside the pass, before our hook.
* GL state touched and restored: program, VAO, `ARRAY_BUFFER`, draw and read FBO, viewport, active texture,
  texture and sampler on units 0 and 1, blend (enable, funcs, equations), depth test, func and mask, colour mask,
  cull, scissor, stencil test, depth clamp, `CLIP_DISTANCE0`. On postcard allocation only, the pixel unpack buffer
  is also saved and restored.

## Tunables (`config/flyaway_perf.properties`)

| key | default | meaning |
|---|---|---|
| `imm_ptl.postcards` | true | master switch |
| `imm_ptl.postcards.style` | paint | `paint` (stylised Balatro-like surface in the destination's colours) or `snapshot` (reprojected snapshot) |
| `imm_ptl.postcards.palette.<ns>.<path>` | (built-in) | paint colours for one destination dimension: `RRGGBB,RRGGBB,RRGGBB` (main, second, gaps) |
| `imm_ptl.postcards.resolution` | 768 | postcard long side in pixels (128..2048) |
| `imm_ptl.postcards.refreshFrames` | 4 | re-capture a directly visible portal at most every N frames |
| `imm_ptl.postcards.maxMemoryMB` | 64 | GPU memory cap, LRU (about 10 postcards at 768) |
| `imm_ptl.postcards.fadeMs` | 350 | swirl->postcard and postcard->live fade time (0 = off) |
| `imm_ptl.postcards.debug` | false | tint postcards magenta (45%); log captures, draws, a 5 s summary and (every 3 s, max 80) a per-pass report of every portal within 96 blocks of the pass camera with IP's/our accept or reject reason |
| `mixin.imm_ptl.IrisCompatPostcardMixin` | true | remove the hooks entirely; `area.imm_ptl=false` also works |
| `mixin.imm_ptl.GameRendererPostcardMainViewMixin` | true | main-view captures, late hook only (the early hook is in `IrisCompatPostcardMixin`) |

## Test scene (lead)

### First real-client run (K_postcards), what it showed

* The nested path did run. `K_postcards.log` has `draw nested ... as swirl` at frames 3-5 (warm-up, no postcard yet)
  and `draw nested ... as postcard` from frame 21 on, with summaries of `postcard=2..5` from 09:27:08 to 09:28:42
  (shot 0). The summaries with `postcard=0` are shots 1-4, where P is not on screen, so that is expected.
* The purple square with a white border inside P in shot 0 is that nested draw. The white border is P's own live
  view around Q (sky and fog). The purple square is Q's postcard, magenta-tinted by `debug=true`. The
  dark mob in front of it occludes it correctly, so the depth test works.
* It looked like a swirl because the newest postcard of Q was the frame-17 capture `rect 81x245 at 0,99`. Q was cut by
  the left screen edge there, so most rays left the captured rectangle and fell back to the swirl. Warm-up ran at
  about 1 fps, so Q was captured only 3 times, and the one complete capture (frame 13, 94x92) was overwritten. The
  completeness rule above fixes this.
* Geometry and IP filters were fine. IP reads the NBT quaternion as `new DQuaternion(rotationB, rotationC, rotationD,
  rotationA)` = (x, y, z, w), so `rotationC:1` is 180 degrees about Y. The nested Q appeared centred in P at 75% of P's
  size, exactly as computed for that rotation. With any other reading, Q would lie behind the pass camera or before
  P's clip plane. Nothing listens to `PortalRenderingPredicateEvent` in the pack. micimmptl's
  `IrisCompatIdleSkipMixin` cancels only outside passes. The `modelView` handed to `onBeforeHandRendering` in a pass
  is the pass's own (IP's `MixinGameRenderer.wrapRenderLevel` passes the inner `LevelRenderer.renderLevel` argument).
* The scene's weakness was shot order: the nested view (shot 0) came before the direct view of Q (shot 2), so the
  nested shot depended on lucky warm-up captures.

### Scene v2

Everything is relative to the player's feet F (eye E = F + 1.62 up). The rear-view rotation is kept on purpose. With
pure translation portals the pass camera looks the same way as the player. Q's front could then be seen through P
only in the same shot direction in which Q is seen directly. Also, only a camera at (almost) the capture position
re-uses the captured rays. A rear-view P whose pass camera is exactly E gives an exact reprojection test: the nested
Q must look like Q in the direct shot.

* Mobs: the harness's mob ring (radius 6) is teleported to y = -1000. They die from void damage without dropping
  loot nearby.
* Q: centre F + (0, 1.5, 6) (south), 3x3. axisW (-1, 0, 0), axisH (0, 1, 0), so normal = W x H = (0, 0, -1):
  it faces the player. It is a translation portal to F + (0, 26.5, 126): 25 up, 120 further south.
* P: centre F + (0, 1.5, -4) (north), 3x3. axisW (1, 0, 0), axisH (0, 1, 0), so normal (0, 0, 1): it faces the
  player. Rotation 180 degrees about Y (`rotationA:0f` = w, `rotationC:1f` = y).
  * Destination F + (0, 1.5, 4), set by `~ ~ ~8` from P.
  * Pass camera: P.dest + R(E - P.origin) = (0, 1.5, 4) + R(0, 0.12, 4) = (0, 1.62, 0) = E. It looks south.
  * Clip plane: z = F + 4, with content beyond it. Q (z = F + 6) is 2 blocks beyond it.
  * Q is not P's reverse: `cannotRenderInMe` is false (Q's origin is 2 blocks off the destination plane).
  * Q's corners pass through P's destination window at local (+-1.0, -0.96..1.04), inside +-1.5. So Q is entirely
    visible in P, at 2/3 of P's size.
* Shot 0 (yaw 0, south): Q directly, head-on at 6 blocks, corners 19-20 degrees off-centre, so the capture is complete
  (coverage 1). Many frames are spent here, so Q is captured fresh.
* Shot 1 (west), shot 3 (east): neither portal is on screen. Shot 4 (pitch 60): Q is about 45 degrees above the top
  edge.
* Shot 2 (yaw 180, north): P at 4 blocks, corners 27-29 degrees off-centre. Inside P is the view south, with Q nested
  in it as a postcard. The capture camera and the display camera are both E, so the reprojection offset is 0.
  Expected result: Q in shot 2 looks like Q in shot 0, magenta-tinted with `debug=true`, and slightly older.
* The warm-up spin (pitch 10) also passes Q (south) and P (north) on every turn.

```
-Dflyaway.harness.setup=tp @e[type=!minecraft:player,distance=..12] ~ -1000 ~;summon immersive_portals:portal ~ ~1.5 ~6 {width:3d,height:3d,axisWX:-1d,axisWY:0d,axisWZ:0d,axisHX:0d,axisHY:1d,axisHZ:0d,dimensionTo:'minecraft:overworld',destinationX:0d,destinationY:100d,destinationZ:0d,Tags:['fa_q']};summon immersive_portals:portal ~ ~1.5 ~-4 {width:3d,height:3d,axisWX:1d,axisWY:0d,axisWZ:0d,axisHX:0d,axisHY:1d,axisHZ:0d,dimensionTo:'minecraft:overworld',destinationX:0d,destinationY:100d,destinationZ:0d,rotationA:0f,rotationB:0f,rotationC:1f,rotationD:0f,Tags:['fa_p']};execute as @e[tag=fa_p,limit=1] at @s run portal set_portal_destination minecraft:overworld ~ ~ ~8;execute as @e[tag=fa_q,limit=1] at @s run portal set_portal_destination minecraft:overworld ~ ~25 ~120
```

With `imm_ptl.postcards.debug=true`, each `[postcards] pass end:` report lists the pass camera, the IP list size, and
every portal within 96 blocks with its verdict. In shot 2 the expected report is `outer <P> ... pass camera (= the
player's eye)`, with Q `ACCEPTED: drawn nested` and P `REJECTED (IP): outside the pass frustum` (P is behind the pass
camera). The capture lines also print `coverage`. Q's captures in shot 0 should read `coverage 1.00`.
