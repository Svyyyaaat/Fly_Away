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

12. **Server-side shortcuts:**
    - Aether/Artifacts accessories: equip and unequip gloves, a ring or a cape; the effect should apply and go away,
      and also show in F5. Re-log with one equipped. Mobs that spawn wearing an Artifacts item must still show and
      drop it (`compat.accessories.*`).
    - A curio with attributes should be applied exactly once after re-logging; check with `/attribute`
      (`compat.curios.CurioInventoryClearCachedIdleMixin`).
    - EF Progressive Difficulty: far mobs should get scaled once you come within range
      (`compat.efprogressivediff.*`).
    - A Simply Swords Hivemind swarm should still follow and attack (`compat.simplyswords.*`).

13. **Round 4 features:**
    - **Postcards:** with shaders on, look at portal Q, then look at it through portal P. It should show a snapshot of Q's view. Walk through P and the snapshot fades to the live view.
    - **Camera:** in F5, Shoulder Surfing and the Epic Fight camera, back up against a lit portal; the camera should stop in front of it.
    - **TACZ:** fire a gun in Shoulder Surfing; the camera should kick.
    - **Hotbar:** select slot 6 and go through a cross-dimension portal; the selected slot should stay on 6.
    - **Fracture blocks:** do a big Epic Fight slam (Meteor Slam); there should be a smaller hitch than before and the blocks should look the same.

14. **Round 5: postcards rework.** Shaders on; set `imm_ptl.postcards.debug=true` if you want to see which surface is which (it tints postcards magenta).
    - Go through a portal into another dimension and walk a few blocks forward without turning around. Come back and look at that portal through another portal: you should see a slightly soft picture of what's on the other side, not a swirl.
    - Walk sideways around a nested portal. The picture should shift smoothly. Near objects can smear a little, but nothing should shear or tear apart.
    - Where the snapshot has no data (very steep angles), it should fade into a blurred version of the picture's edge, not into a purple swirl.
    - A portal you have never been near shows a calm, slowly moving surface in the destination's fog colour.
    - In F5 or Shoulder Surfing, walk out of a portal: your own character must not appear inside the snapshot later.

## Measuring

- Run `/sparkc profiler start --thread *` for 60 s in the same scenario as your old profiles, then
  `/sparkc profiler stop`.
- For the integrated server, also run `/spark profiler start --thread "Server thread"`.
- To get debug counters in `latest.log`, add these to `config/flyaway_perf.properties`:
  - `iris.debugStats=true`: uniform uploads skipped vs done, and framebuffer rebinds.
  - `sable.debugStats=true`: empty physics steps skipped.
