# Fly Away performance pass

This pass reduces CPU time on the client render thread, the client tick and the integrated server without
changing how the game looks or plays. It has three parts:

1. **`mods/flyaway_perf-0.1.0.jar`**: a new patch mod built from `perf-mod/`. It contains 39 mixins in 12 areas.
   Every patch can be switched off without rebuilding (see "Turning patches off" below).
2. **Config changes in `config/`**: only invisible ones. See `perf-mod/docs/configs.md`.
3. **One typo fix in the Solas shader**: in `shaders.properties`, `program.world0/deferred..enabled` became
   `deferred.enabled`. With the typo, turning SSAO off never removed the Overworld deferred pass.

Recommendations that would change visuals or gameplay, and the JVM/memory advice, are documented but **not
applied**. See "Things only you can do" below.

## Where the time went (your spark profile s4gidwMiAz, render thread, 92.6 s)

| Area | Render thread | What happens |
|---|---|---|
| `glBindFramebuffer` (Iris) | 8.4% self | Iris binds its FBO before every entity draw and rebinds the main target after it |
| Portal rendering (Immersive Portals) | 12.3% | The whole world is drawn again per portal, including the shadow pass |
| Portal occlusion query (sync GPU readback) | 1.3% here, **15.2%** in your 2026-10-03 profile | The CPU waits for the GPU to finish the frame |
| Entities / Epic Fight | 10.7% / 5.4% | Mostly per-mesh GL driver work |
| Xaero world map + minimap | ~5.5% | Map writing, texture upload, minimap FBO |
| Client tick | 15.8% | VSS LOD requests (hash collisions), radar curios lookups, animateTick biome lookups, ... |
| Iris custom uniforms | 1.8% | Every shader-pack uniform is re-uploaded on every draw |
| Aether cape check | ~1% | `parallel()` streams on the render thread on every skin lookup |

## Patches (details, profile numbers and risks are in `perf-mod/docs/*.md`)

| Area | Patch | Gain (render thread unless noted) |
|---|---|---|
| Iris | Custom-uniform upload dedup | ~1.2-1.5% |
| Iris | Lazy main-FBO rebind + `glUseProgram` dedup inside Iris entity batches | ~1-2% |
| Portals | Skip IP's synchronous same-frame occlusion read when last frame's "hidden" is fresh | 1.3% (up to ~15%) |
| Portals | `ImmPtlClientChunkMap.getChunk` fast path | ~1.5% |
| Portals | Per-frame cache of micimmptl's Sable straddle portal scans | ~0.6% |
| VSS | Fix colliding `Long` keys (tree bins) in `LodRequestManager` | ~0.6% |
| Xaero | VRAM query throttle, versioned region-highlight hash memo | ~0.5% |
| Client tick | `animateTick` biome lookups through a chunk memo | ~0.7% |
| Curios / Iron's | Memoised Curios item key; per-tick "ring equipped" memo (radar + name tags) | ~0.9% |
| Gliders | Glider-in-curios lookup cache | ~0.3% |
| Epic Fight | VAO attribute re-setup skip, hidden-flag SSBO upload dedup, class lookup cache | ~0.3% |
| aaa_particles | Skip Effekseer draws when nothing is playing; advance effects once per frame, not once per portal pass | up to ~1.1% |
| Colorwheel | Skip empty translucent Flywheel passes | ~0.6% |
| Create/Ponder | Placement-helper item match cache | ~0.2% |
| Aether | Sequential instead of parallel streams in `isCapeVisible` | ~1% |
| Architectury | Cache event-dispatch MethodHandles (was `unreflect` per listener per call) | ~0.1% client, ~2% server tick |
| Server | Spawner biome lookups without per-attempt chunk resolution; `mobsAt` built once | ~1% of server thread |
| Server | Chunk Activity Tracker saves moved off the server thread (autosave/ESC spike source) | spikes |

The estimates add up to roughly **10-15% of render-thread time** on your Oct 11 profile, plus up to another ~14%
in portal-heavy scenes like your Oct 3 profile (the occlusion stall). The test machine has no GPU, so these were not
measured on your hardware. Please profile again with spark (`/sparkc profiler start --thread *`, then
`/sparkc profiler stop`) and compare.

Intentional visible changes (each can be switched off):
- **Portals:** a portal emerging from behind a wall can appear one frame later
  (`imm_ptl.occlusion.noSyncWhenHidden=false` restores stock behaviour).
- **Effekseer effects** (aaa_particles) no longer play N times too fast while a portal is on screen. That was a bug
  (`mixin.compat.EffectDefinitionPortalDeltaMixin=false` restores it).

## Turning patches off

`config/flyaway_perf.properties` is created on first launch.
- Turn off a whole group with `area.<area>=false`. The areas are `vanilla`, `iris`, `imm_ptl`, `epicfight`,
  `xaero`, `sodium`, `flywheel`, `voxy`, `sable`, `vss` and `compat`.
- Turn off one patch with `mixin.<area>.<MixinClassName>=false`.
- If something looks wrong, bisect by area first.

## Things only you can do (biggest remaining wins)

1. **Your PC is paging.** Spark showed 28.8 of 32 GB RAM in use and 41.9 of 42.5 GB of the page file used.
   - Every GC and every texture upload can stall on disk while this is happening. That alone can explain stutter
     and the 611 ms server spikes.
   - Close whatever is holding the memory (Task Manager > Details > Commit size column).
   - Then use the JVM arguments in `perf-mod/docs/configs.md` §6:
     - `-Xmx10G`;
     - G1 with `MaxGCPauseMillis=30` and 16M regions;
     - remove the useless Graal flags `-XX:+EagerJVMCI -Dgraal.TuneInlinerExploration=3`.
2. **Optional visual trade-offs**, with measured shares of your profile (`perf-mod/docs/configs.md` §3):

   | Option | Gain | Cost |
   |---|---|---|
   | Solas `shadowPlayer=false` | ~2.7% | You lose your own shadow |
   | Solas `shadowDistance` 192 -> 128 | ~2% | Shorter shadows |
   | Immersive Portals `maxPortalLayer` | less portal recursion | Fewer nested portal views |
   | Xaero `biome_blending` off | ~0.6-1% | Blockier biome borders on the map |

## Building and testing

- **Build:** `cd perf-mod && ./gradlew installToPack`. It builds against the exact jars in `mods/` and copies the
  jar there.
- **Test tools** (in `perf-mod/tools/`):
  - `launch.py`: a headless NeoForge launcher.
  - `bench.sh` / `abrun.sh`: run the whole pack under Xvfb and Mesa llvmpipe with a scripted benchmark world.
    `-Dflyaway.harness=bench` drives it: fixed seed, NoAI mobs, a nether portal, screenshots, spark on all threads,
    then exit.
  - `spark/`: Java tools that read `.sparkprofile` files (`Tree`, `Callers`, `Spark`).
- **Mixin check:** `-Dflyaway.logMixins=true` logs every applied mixin. All of them applied with the full pack in
  the test client.
