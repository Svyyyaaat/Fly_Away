package dev.flyaway.perf.mixin.sable;

import dev.flyaway.perf.sable.SableStepGate;
import dev.ryanhcode.sable.physics.impl.rapier.box.RapierBoxHandle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts live Rapier box bodies per scene (see SableStepGate). */
@Mixin(value = RapierBoxHandle.class, remap = false)
public abstract class RapierBoxHandleTrackMixin {
    @Shadow @Final private long sceneHandle;

    @Inject(method = "create", at = @At("RETURN"))
    private static void flyaway$added(long sceneHandle, dev.ryanhcode.sable.companion.math.Pose3dc pose, org.joml.Vector3dc halfExtents,
            double mass, CallbackInfoReturnable<RapierBoxHandle> cir) {
        SableStepGate.boxAdded(sceneHandle);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void flyaway$removed(CallbackInfo ci) {
        SableStepGate.boxRemoved(this.sceneHandle);
    }
}
