# In-game checklist for the performance patches

Most of this was checked in the headless test client (Mesa llvmpipe, full pack, Solas on):
- the pack boots;
- a world loads;
- every patch whose target class loaded applied without errors;
- screenshots with patches on and off look identical.

What a GPU-less machine can't check is below. Each item names the patch, so if something looks wrong you can
switch exactly that one off in `config/flyaway_perf.properties` (`mixin.<area>.<ClassName>=false`, or
`area.<area>=false` for a group).

## 10-minute pass

1. **Look through a nether portal with Solas on** (areas `imm_ptl`, `iris`).
   - The view should be unchanged.
   - Walk behind a wall next to the portal and step out. The portal may appear one frame late, no more.
   - Walk through it: teleport and collision should work.
2. **Fight a few Epic Fight mobs in armor** (areas `epicfight`, `iris`). Check armor trims, enchantment glint,
   glowing outlines (spectral arrow), and your first-person arms.
3. **Use Effekseer skills** (Iron's Spells / Epic Fight / NightFall effects; patch `compat.aaa_particles.*`). They
   should appear immediately. With a portal on screen they now play at normal speed; before, they played too fast.
4. **Drive an Aeronautics ship** (areas `sable`, `imm_ptl`):
   - it should collide with terrain;
   - assemble a new ship in a dimension where none existed before; it must fall and collide immediately
     (`sable.RapierPipelineEmptyStepMixin`);
   - drive it through a portal.
5. **Create contraptions** (Colorwheel patch): they should render in the world and in shadows. Holding a shaft or
   cog while looking at a shaft should show the placement ghost.
6. **Xaero map** (`xaero.*`):
   - the world map and minimap update when blocks change;
   - claim highlights redraw;
   - with Iron's invisibility ring equipped, the radar and name tags show invisible mobs; unequip it and they hide
     again.
7. **Ambient particles in the Nether** (spores, ash): they should still appear (`vanilla.ClientLevelAnimateTickBiomeMixin`).
8. **Shader reload and resource reload** (Iris R key, F3+T): sky, fog (water, lava, blindness) and time of day should
   look right afterwards (`iris.*` uniform dedups).
9. **Pause with ESC, quit, and re-open the world**:
   - the log should say `... (off-thread, flyaway_perf)` for Chunk Activity Tracker;
   - there should be no `*.flyaway-tmp` files left in `<world>/chunk_activity_info/`.
10. **Relics mantles**: Glitchy Mantle should let you walk on air and phase; Leafy Mantle should let you walk on
    leaves (`compat.relics`).
11. **Gliders**: a glider in the chest slot and one in the Curios back slot should both work (`compat.vc_gliders`).

## Measuring

- Run `/sparkc profiler start --thread *` for 60 s in the same scenario as your old profiles, then
  `/sparkc profiler stop`.
- For the integrated server, also run `/spark profiler start --thread "Server thread"`.
- To get debug counters in `latest.log`, add these to `config/flyaway_perf.properties`:
  - `iris.debugStats=true`: uniform uploads skipped vs done, and framebuffer rebinds.
  - `sable.debugStats=true`: empty physics steps skipped.
