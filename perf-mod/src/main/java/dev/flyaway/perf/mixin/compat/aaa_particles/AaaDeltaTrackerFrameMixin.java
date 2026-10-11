package dev.flyaway.perf.mixin.compat.aaa_particles;

import dev.flyaway.perf.compat.AaaParticlesState;
import mod.chloeprime.aaaparticles.common.util.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Frame counter for the aaa_particles patches. aaa_particles calls its DeltaTracker.run() exactly once per
 * Minecraft.runTick (before rendering) to compute the per-frame simulation delta, so it is the natural frame boundary.
 * No behaviour change on its own.
 */
@Mixin(value = DeltaTracker.class, remap = false)
public abstract class AaaDeltaTrackerFrameMixin {
    @Inject(method = "run()V", at = @At("HEAD"))
    private static void flyaway$newFrame(CallbackInfo ci) {
        AaaParticlesState.onNewFrame();
    }
}
