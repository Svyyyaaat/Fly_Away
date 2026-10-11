package dev.flyaway.perf.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.flyaway.perf.iris.IrisFboDeferral;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Iris ExtendedShader.clear() rebinds the main render target after every draw; the next draw's apply() binds an
 * Iris gbuffer FBO again. Inside an Iris batch scope we still run bindWrite(false) (so Iris' isMainBound tracking,
 * Veil's main-target wrapper and every other Java-side effect happen exactly as before) but the resulting
 * glBindFramebuffer is deferred by {@link IrisFboDeferral} until something actually depends on the binding.
 * Outside the scope (Sable sub-levels, Epic Fight compute meshes, sky, particles...) nothing changes.
 * Expected: removes up to both binds of every batched draw (~1-1.7% of the render thread in the user profile).
 * Disable: mixin.iris.ExtendedShaderLazyRebindMixin=false.
 */
@Mixin(value = ExtendedShader.class, remap = false)
public abstract class ExtendedShaderLazyRebindMixin {
    @WrapOperation(
            method = "clear()V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;bindWrite(Z)V"))
    private void flyaway$deferMainRebind(RenderTarget target, boolean setViewport, Operation<Void> original) {
        if (IrisFboDeferral.depth == 0 || setViewport) {
            original.call(target, setViewport);
            return;
        }
        IrisFboDeferral.deferNextBind = true;
        try {
            original.call(target, setViewport);
        } finally {
            IrisFboDeferral.deferNextBind = false;
        }
    }
}
