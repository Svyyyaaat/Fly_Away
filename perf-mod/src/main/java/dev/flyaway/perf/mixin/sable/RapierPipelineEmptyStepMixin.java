package dev.flyaway.perf.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.sable.SableStepGate;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.sublevel.KinematicContraption;
import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable steps the native Rapier world of EVERY loaded dimension every physics substep, even when that dimension has
 * no sub-level, no kinematic contraption and no box body - i.e. only static terrain voxel colliders. Nothing can move
 * or collide then, so the step has no observable result, but it still costs native broad-phase/query-pipeline time:
 * ~19% of the integrated-server thread in our test world without any ship (8 dimensions loaded).
 * We skip Rapier3D.step in exactly that case. Rapier3D.tick (pre-physics bookkeeping), contraption pose updates,
 * wake-ups and collision processing still run unchanged. As soon as any dynamic body exists the step runs as before;
 * pending static-collider changes are simply integrated on that first step.
 * Disable: sable.skipEmptyPhysicsStep=false or mixin.sable.RapierPipelineEmptyStepMixin=false.
 */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public abstract class RapierPipelineEmptyStepMixin {
    @Shadow @Final private Int2ObjectMap<ServerSubLevel> activeSubLevels;
    @Shadow @Final private Object2ObjectMap<KinematicContraption, ?> activeContraptions;
    @Shadow @Final private ReferenceList<PhysicsPipelineBody> queuedWakeUps;

    @WrapOperation(method = "physicsTick", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;step(JD)V"))
    private void flyaway$skipEmptyStep(long scene, double timeStep, Operation<Void> original) {
        boolean empty = SableStepGate.ENABLED && this.activeSubLevels.isEmpty() && this.activeContraptions.isEmpty()
                && this.queuedWakeUps.isEmpty() && !SableStepGate.hasBoxes(scene);
        SableStepGate.count(empty);
        if (!empty) original.call(scene, timeStep);
    }

    @Inject(method = "dispose", at = @At("HEAD"))
    private void flyaway$forgetScene(CallbackInfo ci) {
        try {
            SableStepGate.sceneDisposed(((RapierPhysicsPipelineInvoker) this).flyaway$sceneHandle());
        } catch (RuntimeException ignored) {
            // scene not initialised
        }
    }
}
