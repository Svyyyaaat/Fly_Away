package dev.flyaway.perf.mixin.vanilla.iris;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.flyaway.perf.iris.IrisFboDeferral;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hardening for the lazy main-target rebind (IrisFboDeferral): materialise a pending rebind before every
 * VertexBuffer.draw. Veil cancels VertexBuffer.draw at HEAD for tessellation shaders and issues a raw
 * glDrawArrays(GL_PATCHES) that bypasses GlStateManager, so our GlStateManager draw hook would never see it.
 * Priority 500 so this runs before Veil's HEAD handler. In the normal Iris path the preceding
 * ExtendedShader.apply already superseded the pending bind, so this is a field read + no-op.
 */
@Mixin(value = VertexBuffer.class, priority = 500)
public abstract class VertexBufferDeferFlushMixin {
    @Inject(method = "draw()V", at = @At("HEAD"))
    private void flyaway$flushDeferredFbo(CallbackInfo ci) {
        if (IrisFboDeferral.depth != 0) IrisFboDeferral.flush();
    }
}
