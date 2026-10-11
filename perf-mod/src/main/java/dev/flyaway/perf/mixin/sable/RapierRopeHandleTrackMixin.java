package dev.flyaway.perf.mixin.sable;

import dev.flyaway.perf.sable.SableStepGate;
import dev.ryanhcode.sable.physics.impl.rapier.rope.RapierRopeHandle;
import java.util.List;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ropes (Create Simulated winches) are native Rapier bodies too and can exist without any sub-level (both ends on
 * world blocks), so they must keep the scene "non-empty" for SableStepGate (review finding: ropes would freeze).
 * RopePhysicsObject.remove() nulls its handle after removing, so remove() runs once per rope.
 */
@Mixin(value = RapierRopeHandle.class, remap = false)
public abstract class RapierRopeHandleTrackMixin {
    @Shadow @Final private long sceneHandle;

    @Inject(method = "create", at = @At("RETURN"))
    private static void flyaway$added(long sceneHandle, double pointRadius, List<Vector3d> points,
            CallbackInfoReturnable<RapierRopeHandle> cir) {
        SableStepGate.boxAdded(sceneHandle);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void flyaway$removed(CallbackInfo ci) {
        SableStepGate.boxRemoved(this.sceneHandle);
    }
}
