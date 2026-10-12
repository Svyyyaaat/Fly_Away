package dev.flyaway.perf.mixin.compat.tp_shooting;

import com.github.exopandora.shouldersurfing.api.math.Vec2f;
import com.github.exopandora.shouldersurfing.client.ShoulderSurfing;
import com.github.exopandora.shouldersurfing.client.ShoulderSurfingCamera;
import com.tacz.guns.client.event.CameraSetupEvent;
import dev.flyaway.perf.compat.CameraTweaks;
import dev.flyaway.perf.mixin.compat.shouldersurfing.ShoulderSurfingCameraAccessor;
import mod.chloeprime.thirdpersonshooting.client.TpsSsPlugin;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * GAMEPLAY/CAMERA FIX (not a perf patch): TACZ camera recoil in Shoulder Surfing.
 *
 * <p>TACZ applies recoil in its ComputeCameraAngles handler CameraSetupEvent.applyCameraRecoil: per call it advances
 * a pitch/yaw spline and rotates the player by the delta (vanilla F5 / first person). tp_shooting @Overwrites that
 * handler: in Shoulder Surfing it does NOT rotate anything, it ASSIGNS the delta to TpsSsPlugin.deltaXRot/deltaYRot,
 * and its SS plugin adds the pending delta to the SS camera rotation only inside SetupCameraRotationEvent, i.e. on
 * the next LocalPlayer.turn() (next frame's mouse handling). That loses the recoil because:
 * <ul>
 *   <li>ComputeCameraAngles fires once per GameRenderer.renderLevel, and Immersive Portals calls renderLevel again
 *       for every visible portal (MyGameRenderer.renderWorldNew; see also micimmptl's CameraShakeMainPassMixin).
 *       The later passes (same millisecond, delta ~0) overwrite the real delta before it is consumed;</li>
 *   <li>the delta is only consumed when turn() reaches Shoulder Surfing (Epic Fight's MixinMouseHandler swallows
 *       turn() while its TPS/lock-on camera is active, NightFall/fdlib/relics can cancel turnPlayer, free look returns
 *       before the event, an unfocused window skips turnPlayer); each pending delta is overwritten by the next one.</li>
 * </ul>
 * Fix: at the end of every applyCameraRecoil call (after tp_shooting's body), move the pending delta straight into
 * the SS camera's aim rotation and clear it, so tp_shooting's plugin adds nothing (no double kick). This is what
 * TACZ itself does for SS (camera.setXRot(getXRot() - delta)) and what vanilla does to the player: same sign,
 * same magnitude, accumulated over every pass, applied immediately. The free-look offset is preserved.
 * Disable: camera.taczRecoilInShoulderSurfing=false or mixin.compat.TaczRecoilShoulderSurfingMixin=false
 */
@Mixin(value = CameraSetupEvent.class, remap = false)
public abstract class TaczRecoilShoulderSurfingMixin {
    @Unique private static boolean flyaway$broken;

    @Inject(method = "applyCameraRecoil", at = @At("TAIL"))
    private static void flyaway$recoilToShoulderCamera(ViewportEvent.ComputeCameraAngles event, CallbackInfo ci) {
        if (!CameraTweaks.TACZ_RECOIL_IN_SHOULDER_SURFING || flyaway$broken) return;
        TpsSsPlugin plugin;
        try {
            plugin = TpsSsPlugin.getInstance();
        } catch (RuntimeException e) { // SS did not load tp_shooting's plugin: leave tp_shooting's behaviour alone
            flyaway$broken = true;
            return;
        }
        double dx = plugin.deltaXRot;
        double dy = plugin.deltaYRot;
        if (dx == 0.0 && dy == 0.0) return;
        ShoulderSurfing ss = ShoulderSurfing.getInstance();
        if (ss == null || !ss.isShoulderSurfing()) return; // tp_shooting only fills the deltas while shoulder surfing
        ShoulderSurfingCamera camera = ss.getCamera();
        if (camera == null) return;
        if ((Object) camera instanceof ShoulderSurfingCameraAccessor acc) {
            Vec2f rot = acc.flyaway$getRotation();
            acc.flyaway$setRotation(rot.add((float) dx, (float) dy).clampX(-90.0F, 90.0F));
        } else { // accessor disabled: public API (also clears an active free-look offset)
            camera.setXRot(Math.max(-90.0F, Math.min(90.0F, camera.getXRot() + (float) dx)));
            camera.setYRot(camera.getYRot() + (float) dy);
        }
        plugin.deltaXRot = 0.0;
        plugin.deltaYRot = 0.0;
    }
}
