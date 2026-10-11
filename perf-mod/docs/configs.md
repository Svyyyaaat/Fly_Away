# Fly Away - config/ audit (CPU: render thread, client tick, integrated server)

Scope: all 434 entries in `config/` were classified against the mod ids actually shipped in `mods/`
(parsed from every jar's `neoforge.mods.toml` / `fabric.mod.json`, including jar-in-jar), and every
performance-relevant config was read and, where the meaning of a key was unclear, checked against the
decompiled mod source. Profile numbers are % of the Render thread in the user's spark profile
(`user-profile-s4gidwMiAz`, 92.6 s, Iris + Solas, Immersive Portals active).

Result in one line: the pack's configs are already close to optimal for CPU. Almost every big lever that
remains is a visible trade-off (listed below with expected gain). Only two invisible changes were applied.

## 1. Applied changes (invisible)

| File | Key | Old -> New | Why |
|---|---|---|---|
| `config/epicfight-client.toml` | `[ingame] use_persistent_buffer` | `false` -> `true` | Epic Fight's compute-shader skinning (`use_compute_shader = true`, already on) then writes the per-mob pose matrices + hidden-part flags straight into a persistently mapped, fenced ring buffer (`MappedBuffer`/`BuffersPool`) instead of a `glBufferSubData` upload per mob per frame (and per shadow / portal pass). Same pixels; the mod's own tooltip recommends it ("reduces matrix uploading cost"). Auto-falls back to the old path unless the context is OpenGL >= 4.6 (`ComputeShaderProvider.checkIfSupports()`). Expected: a few us per animated EF mob per pass (0.1-0.3 ms/frame in fights with 20-30 mobs). Revert: set back to `false` (also in-game: Epic Fight settings > Graphics > "Persistent Mapped Buffer") if any entity flickers/garbles. |
| `config/voxy-client.toml` | `sectionRenderDistance` | `16` -> `8` | Neo-Voxy keeps two copies of its settings. `voxy-config.json` is authoritative on start (`ModConfigEvent.Loading -> syncFromVoxyConfig()` copies JSON -> TOML in memory), and it says `section_render_distance: 8.0`, `service_threads: 1`. The TOML on disk was stale (16 / 5). Any TOML *reload* (file watcher / config screen save -> `Reloading -> syncToVoxyConfig()`) would silently push 16 (= 512-chunk LOD radius, 4x the LOD area) and 5 worker threads into the live config. Aligning the TOML changes nothing today and removes that trap. |
| `config/voxy-client.toml` | `serviceThreads` | `5` -> `1` | same as above |

No other file was modified (format/comments untouched; only those three values changed).

## 2. Reviewed and left as-is (already optimal or only visible changes possible)

| Config | Relevant values | Verdict |
|---|---|---|
| `sodium-options.json` | builder threads 0 (=auto: `clamp(max(16/3, 16-6),1,10)` = 10 threads at Java priority 3 = Windows below-normal), defer `ALWAYS`, `animate_only_visible_textures`, `use_fog_occlusion`, `use_entity_culling`, `use_block_face_culling`, `use_no_error_g_l_context` all true, render-ahead 3 | Best CPU settings already. Builder threads run below normal priority, so they don't steal from the render thread. |
| `sodium-extra-options.json`, `sodium-extra.properties`, `sodium-mixins.properties` | everything on, `steady_debug_hud` on | Only visual toggles left (animations/particles/fog) -> trade-offs only. |
| `reeses-sodium-options` | (no config file; UI only) | - |
| `iris.properties` | `maxShadowRenderDistance=32`, `colorSpace=DISPLAY_P3` | See section 4: with Solas the 32 is **not used** for terrain/entities (Solas uses `shadow.culling=reversed` + `shadowDistance=192`, so Iris culls the shadow pass with a 192-block box; the slider value only feeds Voxy's distant-beacon shadow casters). `DISPLAY_P3` adds one color-space compute pass per frame (visible change, optional). |
| `entityculling.json` | tracing 128, sleepDelay 10, hitboxLimit 50, captureRate 5, tickCulling on, safeMode on | The cull task runs on its own thread (not the render thread); entities beyond `tracingDistance` are *never* culled, so lowering it would cost FPS. Big bosses (hitbox > 50) are already skipped. Defaults are right. |
| `moreculling.toml` | blockstate/item-frame/sign/painting/beacon/cloud/rain culling on | Already all on; `leavesCullingMode` changes look -> left. |
| `chloride-client.toml` | entity cull 4096 (= 64 blocks, sqrt; with Sable installed the Y limit is ignored), BE cull 64 blocks/32 Y | Already aggressive (user choice). |
| `immediatelyfast.json` | all regular optimizations on | Experimental ones ("rendering glitches may occur") left off on purpose. |
| `badoptimizations.txt` | all caches on, lightmap 80 / sky color 3 | Defaults, all on. |
| `modernfix-mixins.properties`, `modernfix-common.toml` | empty = defaults | Non-default options are load-time/memory or risky (see trade-offs). |
| `ferritecore-mixin.toml` | defaults (`compactFastMap=false`, it's slower at runtime) | OK. |
| `lithium.properties` | empty = all defaults on | OK. |
| `c2me.toml` | all `default`: parallelism 7, thread priority LOW (Windows below-normal + power-throttling) | Good; worker priority already below the render/server threads. |
| `servercore/config.yml`, `optimizations.yml` | dynamic perf off, activation range off, vanilla mob caps, `reduce-sync-loads` + `cache-ticking-chunks` on | Everything still off changes gameplay (see trade-offs). |
| `immersive_portals.json` | `maxPortalLayer 5`, `portalRenderLimit 200`, `enableClientPerformanceAdjustment true`, `visibilityPrediction true`, `sharedBlockMeshBufferOptimization true`, `indirectLoadingRadiusCap 8` | Client perf adjustment (auto-throttle under low FPS) already on. Remaining knobs are visible (trade-offs). |
| `micimmptl-common.toml` / `micimmptl-neovoxy.properties` | `remoteWorldAmbientTickInterval=0`, `remoteWorldMistParticles=false`, `endSeaGlow=false`, `portalViewScissor=true`, `portalViewSharesCamera=true`, `plainSpawnerMobs=true` | Already tuned for CPU. `portalViewReuse` / `portalViewShadowSkip` are marked experimental by the author (left off). |
| `voxy-config.json` | LOD radius 8 (=256 chunks), 1 service thread, render_pressure 2, experimental features off | OK (see applied change for the TOML mirror). |
| `voxy-server.toml`, `vss-*.json` | VSS server generation for LOD on (radius 128 chunks), client prediction off | LOD coverage = visible; see trade-offs. |
| `xaero/world-map/profiles/default.cfg`, `xaero/minimap/profiles/default.cfg`, `xaeroplus.txt` | minimap uses world-map chunks, XaeroPlus "Minimap FPS Limiter" on at 20 fps, `map_writing_distance -1`, biome blending on | Limiter already on. World-map writing/biome blending cost is visible to change (trade-offs). |
| `sable-*.toml`, `simulated-*.toml`, `aeronautics-server.toml` | `sub_level_substeps_per_tick = 2` (default), skylight shadows of sub-levels already off | Physics substeps change simulation behaviour -> not touched. |
| `flywheel-client.toml`, `vanillin-client.toml` | backend `DEFAULT` (indirect when GL 4.6), `limitUpdates=true`, workers auto | Best settings. |
| `lambdynlights.toml` | `fancy`, scheduler `culling`, adaptive ticking (background sleep, 5/8 chunks) | All defaults; in this profile LambDynLights is ~0.03 %. `fast`/`fastest` are visible (light lag). |
| `particle_core_config.toml`, `..._disabled_optimizations_v2.json`, `aaa_particles-client.toml` | all particle_core optimizations enabled, async ticking on, AAA GC hook off | Best. |
| `epicfight-client.toml` | compute shader on (+ persistent buffer now) | Rest is visual/gameplay. |
| `skinlayers.json` | `renderDistanceLOD 14`, `fastRender true` | Default; lowering is visible (trade-off). |
| `notenoughanimations.json`, `cameraoverhaul.toml`, `fallingleaves-client.toml`, `presencefootsteps/userconfig.json` (`maxSteppingEntities 50`), `sounds/*` | defaults | Each < 0.1 % in the profile; nothing worth changing. |
| `jade/*` | defaults | negligible. |
| `neoforge-client.toml` | `experimentalForgeLightPipelineEnabled=false` | Correct (Sodium does lighting). |
| `fml.toml`, `neoforge-common/server.toml` | defaults | startup only. |
| `tacz-client.toml`, `tacz_x_guns_lights_addon-client.toml`, `cataclysm-client.toml`, `mowziesmobs-client.toml`, `efn-client.toml`, `irons_spellbooks-client.toml`, `aether-client.toml` ... | visual effects / UI | Only visible toggles. |
| everything else that is kept (see section 7) | gameplay, worldgen, UI, recipes | No CPU-relevant switches with zero gameplay impact. |

## 3. Optional trade-offs (NOT applied) - visible or gameplay-affecting

Ordered by expected render-thread gain (profile numbers in brackets).

| Where | Change | Expected gain | Cost |
|---|---|---|---|
| Solas `shaders.properties` | `shadowPlayer=false` | **~2.7 %** render thread (`ShadowRenderer.renderPlayerEntity` 2.75 %: the full player model incl. Epic Fight mesh + 3D skin layers is drawn again in every shadow pass, and again per portal view) | You lose your own shadow. |
| Solas option `shadowDistance` 192 -> 128 | fewer shadow-pass sections | **~1.5-2.5 %** (shadow terrain 3.4 % + Colorwheel/Flywheel shadow 1.9 %, area scales with distance^2 -> about half of that) | Shadows end at 128 blocks instead of 192. |
| Xaero World Map `biome_blending=false` | no per-pixel biome blend while building map textures | ~0.6-1 % (`MapPixel.getPixelColours` 1.26 %, biome color 0.6 %) | Map/minimap colors at biome borders become blocky. |
| Xaero World Map `map_writing_distance` -1 -> 8 | fewer chunks re-scanned per frame by `MapWriter` | ~0.5 % (`MapWriter.onRender` 1.0 %) | Far terrain inside render distance is mapped only when you come closer. |
| `vss-client-config.json` `enableXaeroMapBridge=false` | stops VSS from writing server LOD columns into Xaero's map | unmeasured; feeds the same Xaero region processing above (3.9 % `MapProcessor.onRenderProcess`) | World map no longer shows LOD-only (unvisited) terrain. |
| `immersive_portals.json` `maxPortalLayer` 5 -> 3 | caps nested portal recursion | only when portals are visible through portals; portal views are 12 % of the frame | Nested portal views stop at depth 3. |
| `micimmptl-neovoxy.properties` `portalViewShadowSkip=true` | skip the shader shadow pass in portal views into Nether/End | up to one shadow pass per such portal view (second `renderShadows` call is ~2 %) | Author marks it experimental; Solas uses the shadow pass for floodfill voxelization, the mod then keeps the pass anyway. |
| `iris.properties` `colorSpace=SRGB` | removes Iris' color-space conversion pass | ~0.05 ms CPU, more GPU | Only correct if the monitor is not wide-gamut; changes colors otherwise. |
| `skinlayers.json` `renderDistanceLOD` 14 -> 8 | 3D skin layers only up close | ~0.2-0.4 % (skinlayers 0.49 % incl.) | 2D layers visible from 8 blocks. |
| `lambdynlights.toml` `mode = "fast"` | dynamic lights update at reduced rate -> fewer section rebuilds | small on render thread; less chunk-builder churn with many torches/items/gun flashes | Moving lights lag. |
| `chloride-client.toml` / Sodium-extra particles | `[particles]` off for rain splash / block cracking, or Sodium-extra `particles` | `ClientLevel.doAnimateTick` is 1.7 % | fewer ambient particles. |
| `modernfix-mixins.properties` `mixin.perf.faster_item_rendering=true` | skips hidden faces of flat items in GUI | ~0.1-0.3 % (hotbar + inventories) | Off by default upstream because of mod incompatibilities; must be tested with this pack. |
| `modernfix-mixins.properties` `mixin.perf.dynamic_resources=true` | lazy model baking | 1-2 GB less heap (helps the memory problem), **not** CPU | Known incompatibilities; first-use model hitches. Not recommended for this pack. |
| `c2me.toml` `globalExecutorParallelism = 4` | fewer worldgen workers | less CPU contention while flying into new terrain (helps render thread on 8C/16T only during heavy gen) | chunk generation slower. |
| `sodium-options.json` `chunk_builder_threads` 0 -> 6 | fewer mesh workers (auto = 10) | less contention with c2me/Voxy/GC | chunk updates appear slower. |
| `voxy-config.json` `service_threads` 1 -> 2 | not a saving: faster LOD building at higher CPU | - | - |
| `vss-server-config.json` `generationConcurrencyLimitGlobal` 32 -> 8, or `enableChunkGeneration=false` | integrated server generates fewer chunks just for LODs | integrated-server CPU (worldgen) | LODs fill in slower / only from visited terrain. |
| `servercore/config.yml` `activation-range.enabled: true` | Paper-style entity activation range on the integrated server | large server-tick saving with many mobs | Mobs far from the player tick at 1/20 rate -> farms/AI behave differently. Gameplay change. |
| `servercore/config.yml` `dynamic.enabled: true` | auto-lowers simulation/view distance & mobcap when MSPT > 35 | protects against TPS drops | gameplay (mob caps, simulation distance) changes under load. |
| `servercore/optimizations.yml` `fast-biome-lookups: true`, `cancel-duplicate-fluid-ticks: true` | cheaper spawning / fluid ticks | small server-tick gain | minor non-vanilla behaviour. |
| `sable-server.toml` `sub_level_substeps_per_tick` 2 -> 1 | halves Sable physics steps | big server-side saving with many ships | physics accuracy/stability of Aeronautics ships. |

## 4. Iris / Solas shadow distance - what is actually used

* Solas sets `shadowDistance = 192.0`, `shadowMapResolution = 2048`, `entityShadowDistanceMul = 0.05`
  (lib/common.glsl), and in `shaders.properties`: `shadow.culling=reversed`, `shadowEntities=false`,
  `shadowBlockEntities=false`, `shadowPlayer=true`, `voxelizeLightBlocks=true`.
  There is no `<pack>.zip.txt` next to the zip, so these defaults are what runs.
* `const float shadowDistanceRenderMul = 1.0;` is declared in `programs/final.glsl`, but Iris only scans
  shadowcomp/begin/prepare/gbuffers/deferred/composite programs (+ computes) for const directives
  (`ProgramSet.locateDirectives`), **not** `final`. So the multiplier stays -1 and the Iris shadow-distance
  slider is not forced.
* With `shadow.culling=reversed`, `ShadowRenderer.createShadowFrustum` turns a -1 multiplier into 1.0 and builds a
  `SafeZoneCullingFrustum` whose distance culler is `BoxCuller(halfPlaneLength = 192)`; Colorwheel does the same
  (`ShadowCulling.cullDist = min(192, renderDistance)`). The user setting `maxShadowRenderDistance=32` (512 blocks)
  is only used for the `else` branch (non-reversed packs) and for `ShadowRenderer.renderDistance`, which in this pack
  only Voxy reads (`MixinShadowRenderingState`: distant beacon beams as shadow casters up to 32 chunks).
  -> Effective shadow terrain range is 192 blocks (12 chunks) regardless of the 32. Changing it would not help
  Solas; it was left untouched (it matters if the user switches packs).

## 5. Solas - CPU-side review (recommendations only; the shader is the user's)

1. **Shadow player** (`shadowPlayer=true`): 2.75 % of the render thread. Biggest single shader-side CPU item.
   Options: accept, or `shadowPlayer=false`.
2. **Shadow distance 192**: the CPU pays per chunk section draw in the shadow pass (+ Flywheel/Colorwheel
   instances). 128 roughly halves that part (section 3). `shadowMapResolution` costs GPU only.
3. Entity / block-entity shadows are already off (`shadowEntities=false`, `shadowBlockEntities=false`,
   `SHADOW_ENTITIES` undefined) - that is the most important CPU saving and it is in place. If SHADOW_ENTITIES is
   ever enabled, keep `entityShadowDistanceMul` low (0.05 = 9.6 blocks).
4. **Immersive Portals multiplies everything**: each visible portal re-runs the whole Iris pipeline including the
   shadow pass and all composites. Any per-frame shader cost is paid (1 + visible portal views) times.
5. **Pass count** (overworld): shadow, shadowcomp (compute, floodfill), deferred, deferred1 + deferred1_a
   (compute), composite, composite1 + composite1_a (compute, VL), composite3, composite13 (bloom tiles),
   composite14, composite15, final = 13 dispatches. Iris `CompositeRenderer.renderAll` = 2.26 % of the render
   thread (framebuffer binds, uniform uploads, mipmap generation). Merging trivially chainable passes
   (e.g. composite14+15) saves ~0.1-0.2 % each.
6. **Mipmaps**: `colortex0MipmapEnabled`/`colortex1MipmapEnabled` are declared in several passes; each declaration
   makes Iris call `glGenerateTextureMipmap` after that pass (0.43 % self in the profile). Declare them only in the
   pass that feeds the sampler that needs LODs (bloom in composite13).
7. **Typo**: `program.world0/deferred..enabled=SSAO` (double dot) - the overworld `deferred` pass is never disabled
   when SSAO is turned off. Harmless with the default (SSAO on); fix to `program.world0/deferred.enabled=SSAO` so
   turning SSAO off really removes the pass.
8. Custom uniforms: 34 `uniform.*`/`variable.*` definitions (biome smoothers, Aether time solver). Evaluated once
   per frame by Iris' expression engine; not visible in the profile (< 0.1 %). No action needed.
9. `voxelizeLightBlocks=true` + `VOXEL_VOLUME_SIZE 192` (floodfill): GPU cost mostly; on the CPU it makes Iris
   draw light-emitting block entities in the shadow pass. Fine.

## 6. JVM / memory recommendations

Current: MS OpenJDK 21.0.3, `-XX:+UseG1GC -XX:+UnlockExperimentalVMOptions -XX:+EagerJVMCI
-Dgraal.TuneInlinerExploration=3 -Xms512m -Xmx12104m`. Spark: heap used 8.8 GB / 12.4 GB committed, G1 Eden
grown to **7.5 GB**, survivors 255/260 MB (full -> premature promotion), old gen 3.9 GB, young GC 35 ms avg every
6.5 s (allocation rate ~0.7 GB/s), **66 concurrent cycles in 10 min** (one every 9 s), server MSPT max 611 ms.
System: 28.8 / 31.4 GB RAM used, **41.9 / 42.5 GB page file used**.

### 6.1 The machine is paging - fix this first
Commit charge is ~70 GB of a ~74 GB limit. Every GC walks the whole Eden; when Windows has trimmed those pages to
the page file, each young GC page-faults them back in -> the long pauses and the 611 ms server spikes. No flag fixes
that. Steps:
1. Task Manager -> Details -> right-click header -> add **Commit size**; find who holds the ~40 GB that is not
   Minecraft (browsers, Discord/overlays, a leaking app, a zombie `javaw.exe` from an earlier launch). The MC
   process itself should be ~15-17 GB commit (12.4 GB heap + ~0.8 GB metaspace/code + native/driver).
2. Lower `-Xmx`: the old generation holds ~3.9 GB; **10 GB** leaves >2x headroom and frees ~2 GB of commit.
3. Keep the page file system-managed on an SSD. Do not use Large Pages.

### 6.2 Recommended G1 arguments (stutter-oriented)
```
-Xms10G -Xmx10G
-XX:+UseG1GC
-XX:MaxGCPauseMillis=30
-XX:+UnlockExperimentalVMOptions
-XX:G1NewSizePercent=20 -XX:G1MaxNewSizePercent=40
-XX:G1HeapRegionSize=16M
-XX:G1ReservePercent=15
-XX:G1MixedGCCountTarget=4
-XX:G1HeapWastePercent=5
-XX:+PerfDisableSharedMem
-XX:ReservedCodeCacheSize=400M
-XX:HeapDumpPath=MojangTricksIntelDriversForPerformance_javaw.exe_minecraft.exe.heapdump
```
Why:
* `MaxGCPauseMillis=30` + `G1MaxNewSizePercent=40`: stops G1 from growing Eden to 7.5 GB (default target 200 ms
  lets it); pauses become shorter and more even (expect ~15-25 ms every 2-4 s instead of 35 ms every 6.5 s, and far
  less memory touched per GC - important while paging).
* `G1HeapRegionSize=16M`: default for a 12 GB heap is 4 MB regions -> every array >= 2 MB (chunk/LOD/mesh buffers,
  texture data) is a *humongous* allocation, which triggers concurrent cycles (the 66 cycles / 10 min) and fragments
  the heap. 16 MB regions raise the threshold to 8 MB.
* `G1ReservePercent=15`, `G1MixedGCCountTarget=4`, `G1HeapWastePercent=5`: avoid to-space exhaustion and spread
  mixed collections.
* `PerfDisableSharedMem`: no hsperfdata memory-mapped file writes from GC (disk stalls while the disk is busy
  paging). Spark does not need it.
* `ReservedCodeCacheSize=400M`: 245 mods + mixins; if the code cache fills ("CodeCache is full. Compiler has been
  disabled") the JIT stops and CPU time explodes. Cheap insurance.
* `-Xms = -Xmx`: no heap resizing, stable Eden sizing. **Only once the paging is fixed**; while the system is out of
  commit, use `-Xms4G` so the heap is not committed up-front.
* `-XX:+AlwaysPreTouch`: touches all 10 GB at startup (adds a few seconds to launch, removes first-touch page
  faults during play). Worth it only with Xms=Xmx **and** enough free RAM; harmful now (forces 10 GB resident and
  pushes other processes to the already-full page file).
* Remove `-XX:+EagerJVMCI -Dgraal.TuneInlinerExploration=3`: they are GraalVM flags; MS OpenJDK has no Graal JIT,
  JVMCI is not enabled, the property is simply ignored. They do nothing.
* Do not add `-XX:+DisableExplicitGC`: direct-buffer allocation relies on `System.gc()` as a fallback.

### 6.3 Other JVMs
* Update to the latest **JDK 21** build (21.0.3 is from April 2024; later 21.0.x updates carry G1 and C2 fixes).
  Stay on 21 - NeoForge 1.21.1 targets it.
* **Generational ZGC** (`-XX:+UseZGC -XX:+ZGenerational -Xms10G -Xmx10G`, drop all G1 flags) gives sub-ms pauses
  and is the best option against GC stutter on a 16-thread CPU, but needs ~20-30 % more heap headroom and does
  concurrent work on other cores. Try it only after 6.1 is solved.
* **GraalVM (Oracle GraalVM 21, Graal JIT)**: often a few % (sometimes ~10 %) more CPU throughput in modded MC
  thanks to better escape analysis/inlining; costs more JIT CPU/memory during warm-up and has had rare miscompiles
  with heavily mixin-transformed code. Only with GraalVM do the existing Graal flags mean anything. Optional
  experiment; measure with spark.

## 7. Leftover configs (mod not in `mods/`) - removable, NOT deleted

135 entries for about 70 mods that are no longer installed, plus 53 backup files. Deleting them changes nothing at
runtime (no jar reads them); it just declutters.

- AE2: `ae2-client.toml` `ae2-common.toml`
- AI Improvements: `aiimprovements-common.toml`
- Advanced Netherite: `advancednetherite-client.toml` `advancednetherite-common.toml` `advancednetherite-server.toml`
- AeroBlender: `aeroblender-common.toml`
- Ars Nouveau family: `ars_additions/` `ars_additions-common.toml` `ars_additions-server.toml` `ars_aeronautics/` `ars_controle/` `ars_controle-server.toml` `ars_controle-startup.toml` `ars_creo-common.toml` `ars_elemental/` `ars_elemental-client.toml` `ars_elemental-common.toml` `ars_elemental-startup.toml` `ars_nouveau/` `ars_nouveau-client.toml` `ars_nouveau-common.toml` `ars_nouveau-server.toml` `ars_nouveau-startup.toml` `ars_technica/` `ars_technica-common.toml` `ars_unification-server.toml` `ars_zero/` `ars_zero-server.toml`, `arseng-common.toml`, `reliquified_ars_nouveau/`, `starbunclemania/` `starbunclemania-common.toml` `starbunclemania-server.toml`, `sauce-server.toml` `sauce-startup.toml`
- Atmosfera: `atmosfera.json`
- Avaritia: `avaritia-common.toml`
- Awakened Mobs (jar is `.disabled`): `awakenedmobs-common.toml` (keep if you plan to re-enable it)
- BCLib / BetterNether / WorldWeaver: `bclib/` `betternether/` `wover/`
- Better Advanced Tooltips: `better-advanced-tooltips-client.toml`
- Better Archeology: `betterarcheology.jsonc`
- Better Third Person: `betterthirdperson-common.toml`
- Blur: `blur.json`
- Chat Animation: `chatanimation.json`
- Draconic Evolution / BrandonsCore / CodeChickenLib: `brandon3055/` `ccl.cfg`
- Construction Stick: `constructionstick-server.toml` `constructionstick-startup.toml`
- Cosy Critters: `cosycritters.json`
- Critters and Companions: `crittersandcompanions-common.json5`
- Cucumber / Mystical Agriculture: `cucumber-client.toml` `cucumber-common.toml` `cucumber-tags.json` `mysticalagradditions-common.toml` `mysticalagriculture-common.toml`
- Curvy Pipes: `curvy_pipes_default.yaml`
- Deep Aether: `deep_aether-client.toml` `deep_aether-common.toml` `deep_aether-server.toml`
- Deeper and Darker: `deeperdarker-common.toml`
- Distant Horizons: `DistantHorizons.toml`
- Do a Barrel Roll: `do_a_barrel_roll-client.json` `do_a_barrel_roll-server.json`
- Does It Tick: `does_it_tick-common.toml`
- EFX: `efx-common.toml`
- EMF / ETF: `entity_model_features.json` `entity_texture_features.json` `etf_warnings.json`
- End Remastered: `EndRemastered-NeoForge/`
- Flux Networks: `fluxnetworks-client.toml` `fluxnetworks-common.toml` `fluxnetworks-server.toml`
- Footprint Particle: `footprintparticle-client.toml`
- Galosphere: `galosphere-common.toml`
- Ghosts: `ghosts.toml`
- Golem Overhaul: `golemoverhaul.jsonc`
- Guard Villagers: `guardvillagers-client.toml` `guardvillagers-common.toml` `guardvillagers-startup.toml`
- Icarus: `icarus.jsonc`
- Inventory Profiles Next (only libIPN is installed): `inventoryprofilesnext/`
- Leaves Be Gone: `leavesbegone-server.toml`
- Mekanism: `Mekanism/`
- MineColonies / Structurize: `colonypathingedition-common.toml` `minecolonies-client.toml` `minecolonies-common.toml` `minecolonies-server.toml` `minecolonies_compatibility-common.toml` `minecolonies_compatibility-server.toml` `minecolonies_tweaks-client.toml` `minecolonies_tweaks-common.toml` `minecolonies_tweaks-custom_tools.json` `minecolonies_tweaks-server.toml` `structurize-client.toml` `structurize-server.toml`
- Mob Grinding Utils: `mob_grinding_utils-server.toml`
- Mobs of Mythology: `mobs_of_mythology.properties`
- Nyf's Spiders: `nyfsspiders.toml`
- Oritech: `oritech-client.toml` `oritech-common.toml` `oritech-startup.toml`
- Particle Effects: `particle_effects.json5`
- Powah: `powah.json5`
- Royal Variations: `royalvariations.toml`
- Satisfying Buttons: `satisfying_buttons-client.toml`
- Simply Bows: `simplybows/`
- Sinytra Connector: `connector.json`
- Smooth Swapping: `smoothswapping.json`
- Sophisticated Backpacks: `sophisticatedbackpacks-common.toml` `sophisticatedbackpacks-server.toml`
- Sound Physics Remastered: `sound_physics_remastered/`
- Sub-level render distance mod (not installed): `ssrd-common.toml`
- Subtle Effects: `subtle_effects/`
- Tornado Physics: `tornadophysics-common.toml`
- Travel Optics: `traveloptics/`
- Twilight Forest: `twilightforest-client.toml` `twilightforest-common.toml`
- Umapyoi: `umapyoi-client.toml` `umapyoi-common.toml`
- Vinery: `vinery.toml`
- Voxy WorldGen v2: `voxyworldgenv2.json`
- WATUT: `watut-client.toml` `watut-common.toml` `watut-item-arm-adjustments.json` `watut-server.toml`
- Wakes: `wakes-client.toml`
- Water Drip Sound: `waterdripsound.json`
- Weather2: `Weather2/` (CoroUtil itself is installed; keep `CoroUtil/`)
- YIGD: `yigd.json`
- Yo Hooks: `yo_hooks/`
- Zombie Awareness: `zombieawareness/`
- removed lights mod (no installed jar reads it; its "HIGH"/96-light settings are inert): `veil_iris_lights.json`
- Inside kept folders: `cristellib/` has sub-folders for removed mods (ae2, ars_*, betterarcheology, betternether, ...).
- `spark/profile-*.sparkprofile` (3 old profiles) - data, not config.

Backups written by NeoForge/Chloride when a file was corrected (all removable):
`aces_spell_utils-common-1.toml.bak ars_nouveau-server-1.toml.bak avaritia-common-1.toml.bak carryon-common-1.toml.bak cataclysm-client-1.toml.bak chloride-client.toml_backup1 chloride-client.toml_backup2 colonypathingedition-common-1.toml.bak create_dragons_plus-common-1.toml.bak create_dragons_plus-server-1.toml.bak create_dragons_plus-simulated-extension-server-1.toml.bak create_enchantment_industry-server-1.toml.bak create_enchantment_industry-server-2.toml.bak createaddition-common-1.toml.bak createaddition-common-2.toml.bak createpropulsion-client-1.toml.bak createpropulsion-common-1.toml.bak crittersandcompanions-common.toml.old discerning_the_eldritch-common-{1..5}.toml.bak flyingworldgen-common-1.toml.bak galosphere-common-1.toml.bak guardvillagers-common-1.toml.bak hardcorerevival-common-1.toml.bak irons_spellbooks-client-1.toml.bak irons_spellbooks-server-1.toml.bak lootr-client-1.toml.bak lootr-common-1.toml.bak minecolonies-server-1.toml.bak mod_menu-client-{1..5}.toml.bak neoforge-common-1.toml.bak openpartiesandclaims-server-{1,2}.toml.bak overflowingbars-client-1.toml.bak reliquary-common-1.toml.bak royalvariations-1.toml.bak sable-client-1.toml.bak sable-common-1.toml.bak sauce-server-1.toml.bak simulated-client-1.toml.bak simulated-server-1.toml.bak sophisticatedstorage-client-1.toml.bak tacz-client-1.toml.bak tacz-common-1.toml.bak tornadophysics-common-1.toml.bak voxy-client-1.toml.bak`

Kept (246): every other entry belongs to an installed mod (incl. non-obvious ones: `crepperoverhaul-client.jsonc`
= Creeper Overhaul's own typo, `transition.json`/`trender.json` = TRansition/TRender libs inside EntityCulling/NEA,
`waterconfig.toml` = Chloride's bundled gamelib, `vanilla_structures/` = Cristel Lib, `towns_and_towers/`,
`apotheosis/apothic_attributes.cfg` = Apothic Attributes, `fabric/indigo-renderer.properties` = Forgified Fabric
API, `efn_aaa_warning.json` = NightFall, `xaerohud.txt`/`xaeropatreon.txt`, `yacl.json5`).

## 8. For the lead to test
* Epic Fight persistent buffer: `ComputeShaderProvider.checkIfSupports()` enables it only when the context
  reports OpenGL >= 4.6 (log: `[Computer Shader Acceleration] OpenGL Version: ...`). llvmpipe usually reports 4.5,
  so under Xvfb the old upload path is used and the change is a no-op there - it cannot break the test run, but it
  also cannot be verified there. On the user's GPU (GL 4.6 on NVIDIA/AMD/Intel Windows drivers): spawn several EF
  mobs, view them with shaders on and through a portal - no flicker / wrong poses.
* Voxy: settings screen still shows LOD distance 8 and 1 service thread (unchanged behaviour).
