package dev.flyaway.perf.mixin.sable;

import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public interface RapierPhysicsPipelineInvoker {
    @Invoker("getSceneHandle")
    long flyaway$sceneHandle();
}
