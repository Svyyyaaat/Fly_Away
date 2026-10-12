# Camera fixes (gameplay, not performance)

Two user-reported third-person camera bugs. Unlike the rest of flyaway_perf these patches change behaviour on
purpose, exactly as described below and nothing else. Both have a tunable in `config/flyaway_perf.properties` and can
also be switched off per mixin (`mixin.compat.<MixinName>=false`).

| Tunable | Default | Effect |
|---|---|---|
| `camera.portalsAreWalls` | `true` | third-person cameras stop in front of Immersive Portals portals |
| `camera.taczRecoilInShoulderSurfing` | `true` | TACZ camera recoil is applied to the Shoulder Surfing camera |

All five mixins are client-only and live in `flyaway_perf.compat.mixins.json` (`client`).

---

## 1. Third-person camera goes through portals

### Problem
Standing next to an Immersive Portals portal in vanilla F5 (back or front) or in Shoulder Surfing, the camera ends up
on the other side of the portal plane. Every third-person camera in the pack computes its distance by casting rays
against **blocks only**, so a portal does not stop it. IP then notices the camera is behind a portal and switches to
its *cross-portal view* (`CrossPortalViewRendering.renderCrossPortalView`, called instead of the normal
`GameRenderer.renderLevel` when `enableCrossPortalView` is on): it renders the destination world from a camera
re-projected through the portal. That is IP's intended behaviour, but with the shader and the nether-portal frames it
looks like the user's screenshot (camera behind the frame, destination world smeared). The user wants the camera to
treat portals like walls instead.

Camera-distance code in the pack (all verified with javap on the shipped jars):

| Camera | Where the distance is computed | Ray cast |
|---|---|---|
| vanilla F5 back/front | `Camera.getMaxZoom(F)F` (called from `Camera.setup`) | 8 corner rays (0.1 offset) from the eye, `BlockGetter.clip` each |
| Shoulder Surfing 5.2.0 (all modes / presets, incl. ssrcamerafixes overhead) | `ShoulderSurfingCamera.maxZoom(Camera,Entity,BlockGetter,Vec3,F)D` (static, from `setup`) | 8 corner rays from the eye to the shoulder offset, `IObjectPicker.clip` each |
| Epic Fight TPS camera | `EpicFightCameraAPI.setupCamera(Camera,F)` | 8 corner rays from the eye, `ClientLevel.clip` each (only clip in the method) |
| Epic Fight lock-on camera, TPS zoom transition | `camera.getMaxZoom(4.0F)` | = vanilla |
| IP's own cross-portal camera (`camera1.setup`) | = vanilla | = vanilla |

### Fix
`PortalCameraWalls.clip(level, eye, from, to, blockHit)` (dev.flyaway.perf.compat): for the ray `from -> to` it takes
the portals near the eye (IP's `McHelper.getEntitiesNearby`, the same chunk-rough lookup IP's
`PortalUtils.raytracePortals` uses, plus `GlobalPortalStorage.getGlobalPortals`), ray-casts each with
`Portal.generalRayTrace(from, to, 0.001)` (hit only inside the portal's bounds, crossing from its front side;
same-dimension and dimension-crossing portals alike) and, if a portal is crossed before the block hit, returns a
`BLOCK` hit at the portal plane. Each camera then clamps exactly as it does for blocks, so the camera keeps the same
~0.1 block corner margin in front of the plane that vanilla keeps in front of walls.

Only portals whose front faces the player's eye count (`portal.isInFrontOfPortal(eye)`), so while the eye is within
0.1 of a plane (walking through), the twin portal on the far side does not pull the camera into the head.
Because the camera never crosses the plane any more, IP's cross-portal view never triggers for third person
(its raytrace from the head to the camera finds no portal). First person is not touched.

The portal list is cached for the 8 rays of one camera setup (key: level + exact eye position, max age 20 ms).
Cost: one IP entity lookup (3x3 chunks, Portal class) per third-person camera setup; nothing in first person.

| Mixin | Target | Injection |
|---|---|---|
| `compat.immersive_portals_core.CameraPortalWallMixin` | `net.minecraft.client.Camera#getMaxZoom(F)F` | `@WrapOperation` on `BlockGetter.clip(ClipContext)` |
| `compat.shouldersurfing.ShoulderSurfingPortalWallMixin` | `ShoulderSurfingCamera#maxZoom` (static) | `@WrapOperation` on `IObjectPicker.clip(BlockGetter,ClipContext,BlockCollisionPredicate)`, `@Local(argsOnly)` Entity + partialTick for the eye |
| `compat.epicfight.EpicFightCameraPortalWallMixin` | `EpicFightCameraAPI#setupCamera(Camera,F)` | `@WrapOperation` on `ClientLevel.clip(ClipContext)` |

The SS and EF mixins check `CameraTweaks.IMM_PTL` (ModList) before touching IP classes.

Other mods on the same methods (checked in the mixin index): Sable `camera_zoom.CameraMixin` cancels `getMaxZoom` at
HEAD only in its sub-level view camera type (that mode keeps Sable's own clamp, no portal walls) and redirects
`Vec3.distanceToSqr` (not the clip); Sable `ShoulderSurfingCameraMixin` redirects `Vec3.distanceTo` in `maxZoom`
(not the clip); Create / CameraOverhaul inject around the `getMaxZoom` call in `setup`; nobody touches the clip calls.

Risk: low. A portal that is invisible but placed by a mod as a seamless connection now also stops the camera (the
user asked for every portal). Sable sub-level view camera: no portal walls.

Config-only alternative (not applied): `immersive_portals.json` `"enableCrossPortalView": false` stops IP's
cross-portal rendering but the camera would still be behind the plane (you would see the portal's back side /
the local world), so the wall behaviour needs the mixins.

---

## 2. TACZ recoil missing in Shoulder Surfing

### How the pieces work (TACZ 1.1.8-hotfix-r7, tp_shooting 6.0.0, SS 5.2.0)
* `CameraSetupEvent.initialCameraRecoil` (GunFireEvent, client) builds pitch/yaw spline functions for the shot.
* `CameraSetupEvent.applyCameraRecoil` (ViewportEvent.ComputeCameraAngles) samples the splines by wall time and
  applies the delta since its previous call: `player.setXRot(getXRot() - (value - xRotO))`. In first person / vanilla
  F5 the camera follows the player, hence the kick. TACZ's own SS branch calls `ShoulderSurfing.getInstance()` which
  is binary-incompatible with SS 5 (hence tp_shooting).
* tp_shooting `FixIncompatibleClassChangeInCameraSetupEvent` `@Overwrite`s `applyCameraRecoil` (priority 0): while
  shoulder surfing it only **assigns** `TpsSsPlugin.deltaXRot/deltaYRot = xRotO - value`; its SS plugin
  (`setupCameraRotation`, SetupCameraRotationEvent priority 2005) adds the pending delta to the SS camera rotation and
  zeroes it. That event only fires from `ShoulderSurfingCamera.turn()`, i.e. on the next frame's mouse handling.
* SS renders from its own camera rotation (decoupled camera is on in this pack), so changing the player's rotation does
  nothing visible in SS.

### Why the kick is lost
* `ComputeCameraAngles` fires once per `GameRenderer.renderLevel`, and Immersive Portals calls `renderLevel` again for
  every visible portal (`MyGameRenderer.renderWorldNew`; the user's micimmptl already has `CameraShakeMainPassMixin`
  for the same reason). Each later pass (same millisecond, delta about 0) overwrites the pending delta.
* The pending delta is only consumed if `turn()` reaches Shoulder Surfing: Epic Fight's `MixinMouseHandler` swallows
  `turn()` while its TPS/lock-on camera is active, NightFall/fdlib/relics can cancel `turnPlayer`, SS free look
  returns before the event, and an unfocused window skips `turnPlayer`. Every pending delta that is not consumed in
  time is overwritten by the next one.
No config in tp_shooting, TACZ (`tacz-client.toml`), SS or ssrcamerafixes controls this (checked all four specs).

### Fix
`compat.tp_shooting.TaczRecoilShoulderSurfingMixin`: `@Inject(at = TAIL)` into
`com.tacz.guns.client.event.CameraSetupEvent#applyCameraRecoil(ViewportEvent$ComputeCameraAngles)V` (runs after
tp_shooting's overwritten body; Mixin merges all mixins before any injector runs). If tp_shooting left a non-zero
pending delta and SS is active, the delta is added straight to the SS camera aim rotation
(`ShoulderSurfingCamera.rotation`, via `compat.shouldersurfing.ShoulderSurfingCameraAccessor`, pitch clamped to +-90)
and the pending fields are zeroed, so tp_shooting's plugin adds nothing (no double kick). Effect: same sign and
magnitude as vanilla F5 (TACZ's spline delta, 1:1), accumulated over every render pass, visible in the same frame.
The free-look offset is preserved (if the accessor is disabled it falls back to SS's public setXRot/setYRot, which
also clears free look offset). tp_shooting's `ClientGunFireEvent` still copies the SS camera rotation to the player on
each shot, so follow-up shots climb with the recoil as in vanilla.

Risk: low. If SS did not load tp_shooting's plugin (`TpsSsPlugin.getInstance()` throws) the mixin disables itself.

---

## Test plan (lead, real client)
Mixins apply: start with `-Dflyaway.logMixins=true` and check the log for
`compat.immersive_portals_core.CameraPortalWallMixin -> net.minecraft.client.Camera`,
`compat.shouldersurfing.ShoulderSurfingPortalWallMixin` / `ShoulderSurfingCameraAccessor -> ...ShoulderSurfingCamera`,
`compat.epicfight.EpicFightCameraPortalWallMixin -> ...EpicFightCameraAPI`,
`compat.tp_shooting.TaczRecoilShoulderSurfingMixin -> com.tacz.guns.client.event.CameraSetupEvent`; no
`InvalidInjectionException` (defaultRequire 1).

Portals as walls (build a nether portal, light it, wait for the IP portal):
1. Vanilla F5 back: stand 1 block in front of the portal, facing away from it, so the camera would be behind you
   inside/through the portal. Expected: camera stops ~0.1 in front of the portal surface (closer to the head),
   no destination-world view, no smearing. Walk away: camera extends back to 4 blocks smoothly.
2. Vanilla F5 front (second F5): face the portal from 1 block away; same expectation.
3. Shoulder Surfing (toggle with its key): repeat 1 and 2 with the default shoulder, swap shoulders, the overhead
   preset (ssrcamerafixes shoulder cycle), while aiming a TACZ gun and with free look; camera must stay on the player's
   side of the portal.
4. Epic Fight: switch to battle mode with TPS camera (if enabled in EF config) and with lock-on in F5; same.
5. Walk through the portal in each mode: teleport works, camera follows; while the eye crosses the plane the camera
   may briefly come close to the head (same as walking past a wall corner), never shows the wrong side.
6. Look at a portal from 5+ blocks with the camera not crossing it: unchanged (portal still renders the other world).
7. First person: unchanged. `camera.portalsAreWalls=false`: old behaviour back (camera crosses, IP cross-portal view).

TACZ recoil in Shoulder Surfing:
1. Vanilla F5 with any TACZ rifle: fire single shots / bursts and note the upward kick.
2. Shoulder Surfing (decoupled camera, default pack config): same gun, same fire mode; expected the SS camera kicks up
   (and sideways by the yaw spline) with the same feel, then the camera stays where the recoil put it (as in F5).
3. Repeat 2 while a portal is on screen (several IP render passes per frame) and with Epic Fight battle mode on.
4. Hip fire and aiming (ADS) in SS: kick smaller when aiming (TACZ aimingRecoilModifier), same as F5.
5. Hold free look (SS key) while firing: view still kicks; release free look: camera returns to the aim direction.
6. `camera.taczRecoilInShoulderSurfing=false`: back to tp_shooting's behaviour (little or no kick).
