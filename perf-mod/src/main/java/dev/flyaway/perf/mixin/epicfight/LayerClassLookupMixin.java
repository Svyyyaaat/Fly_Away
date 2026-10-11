package dev.flyaway.perf.mixin.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.epicfight.AnonymousClasses;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.client.renderer.FirstPersonRenderer;
import yesman.epicfight.client.renderer.patched.entity.PatchedLivingEntityRenderer;
import yesman.epicfight.client.renderer.patched.entity.PresetRenderer;

/**
 * What: cache {@code Class.isAnonymousClass()} in Epic Fight's per-frame layer dispatch.
 * Why: for every rendered entity, every frame, Epic Fight walks the vanilla renderer's layer list and calls
 * {@code layer.getClass().isAnonymousClass()} on each layer to map it to its patched layer (renderLayer), plus once
 * per render() for the renderer itself. isAnonymousClass is an uncached native reflection call
 * (getEnclosingMethod0 + InnerClasses parsing); profile: ~0.05% of the render thread.
 * Fix: same answer from a ClassValue (see {@link AnonymousClasses}). Zero behaviour change.
 * Disable: mixin.epicfight.LayerClassLookupMixin=false
 */
@Mixin(value = {PatchedLivingEntityRenderer.class, FirstPersonRenderer.class, PresetRenderer.class}, remap = false)
public abstract class LayerClassLookupMixin {
    @WrapOperation(method = {"render", "renderLayer"}, require = 1,
            at = @At(value = "INVOKE", target = "Ljava/lang/Class;isAnonymousClass()Z"))
    private boolean flyaway$cachedIsAnonymous(Class<?> type, Operation<Boolean> original) {
        return AnonymousClasses.isAnonymous(type);
    }
}
