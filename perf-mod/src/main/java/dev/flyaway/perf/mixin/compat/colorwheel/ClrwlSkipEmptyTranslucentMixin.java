package dev.flyaway.perf.mixin.compat.colorwheel;

import dev.djefrey.colorwheel.ClrwlSafeFlwImpl;
import dev.djefrey.colorwheel.engine.ClrwlDrawManager;
import dev.djefrey.colorwheel.engine.ClrwlEngine;
import dev.djefrey.colorwheel.engine.ShadowRenderingPhase;
import dev.djefrey.colorwheel.indirect.ClrwlIndirectCullingGroup;
import dev.djefrey.colorwheel.indirect.ClrwlIndirectDrawManager;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Colorwheel renders Flywheel's translucent instances in an extra pass: it builds a TranslucentRenderContext (or a
 * ShadowRenderContext with phase TRANSLUCENT) and calls VisualizationManager.renderDispatcher().afterEntities(ctx),
 * i.e. a full VisualizationManagerImpl.render(): task sync, GL state save/restore, and - with Voxy - Voxy's
 * "combined depth" bracket around it (VoxySableDepthShim.beginInPlace/endInPlace: 3 depth copies, a merge blit and a
 * restore blit, plus a dozen glGet* round trips). ClrwlIndirectDrawManager.renderTranslucent then returns immediately
 * when no culling group has translucent or OIT draws - which is the normal case (Create's instances are solid).
 * Profile: colorwheel$injectRenderTranslucents 0.68% of the render thread, 0.03% of it actual colorwheel work,
 * ~0.56% the Voxy depth bracket.
 *
 * <p>Fix: when every live Colorwheel engine uses the indirect backend, has already prepared this frame, and has no
 * translucent/OIT draws, the translucent submit is skipped. Nothing would have been drawn and the depth bracket is
 * designed to leave depth unchanged, so the output is identical. The instanced backend is never skipped.
 */
@Mixin(value = ClrwlSafeFlwImpl.class, remap = false)
public abstract class ClrwlSkipEmptyTranslucentMixin {
    @Inject(method = "submitTranslucentRenderContext", at = @At("HEAD"), cancellable = true)
    private void flyaway$skipEmptyTranslucent(ClientLevel level, Camera playerCamera, Matrix4f modelMatrix, Matrix4f projectionMatrix,
                                              float tickDelta, CallbackInfo ci) {
        if (flyaway$noTranslucentWork()) ci.cancel();
    }

    @Inject(method = "submitShadowRenderContext", at = @At("HEAD"), cancellable = true)
    private void flyaway$skipEmptyShadowTranslucent(ClientLevel level, Camera playerCamera, Vector3d cameraPos, float tickDelta,
                                                    ShadowRenderingPhase phase, CallbackInfo ci) {
        if (phase == ShadowRenderingPhase.TRANSLUCENT && flyaway$noTranslucentWork()) ci.cancel();
    }

    @Unique
    private static boolean flyaway$noTranslucentWork() {
        try {
            if (ClrwlEngine.ENGINES.isEmpty()) return false; // nothing known: let the original decide
            for (ClrwlEngine engine : ClrwlEngine.ENGINES) {
                ClrwlEngineAccessor acc = (ClrwlEngineAccessor) (Object) engine;
                if (acc.flyaway$shouldPrepareFrame()) return false; // this call would run the frame's prepareFrame
                ClrwlDrawManager<?> dm = acc.flyaway$drawManager();
                if (!(dm instanceof ClrwlIndirectDrawManager)) return false;
                for (ClrwlIndirectCullingGroup<?> group : ((ClrwlIndirectDrawManagerAccessor) (Object) dm).flyaway$cullingGroups().values()) {
                    if (group.hasTranslucentDraws() || group.hasOitDraws()) return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
