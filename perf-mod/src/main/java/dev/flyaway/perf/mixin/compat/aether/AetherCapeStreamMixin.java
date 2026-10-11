package dev.flyaway.perf.mixin.compat.aether;

import com.aetherteam.aether.mixin.AetherMixinHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Aether's AetherMixinHooks.isCapeVisible (called from AbstractClientPlayer.getSkin and Player.isModelPartShown,
 * i.e. several times per player per frame and per render pass) builds PARALLEL streams over a one-slot accessory
 * container just to call findFirst(). Every call forks ForkJoin tasks and wakes common-pool workers
 * (~0.95% of the render thread in the user's profile, plus the wake-up cost on other cores).
 * findFirst() on an ordered stream returns the same element sequentially, so forcing parallel=false is identical.
 */
@Mixin(value = AetherMixinHooks.class, remap = false)
public abstract class AetherCapeStreamMixin {
    @ModifyArg(method = "isCapeVisible",
            at = @At(value = "INVOKE", target = "Ljava/util/stream/StreamSupport;stream(Ljava/util/Spliterator;Z)Ljava/util/stream/Stream;"),
            index = 1)
    private static boolean flyaway$sequential(boolean parallel) {
        return false;
    }
}
