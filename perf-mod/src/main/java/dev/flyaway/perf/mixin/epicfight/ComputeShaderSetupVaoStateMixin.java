package dev.flyaway.perf.mixin.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.client.renderer.shader.compute.ComputeShaderSetup;

/**
 * What: keep Epic Fight's per-mesh VAO configured between draws instead of re-specifying and then disabling
 * every vertex attribute on every draw.
 * <p>
 * Why: {@code ComputeShaderSetup.draw} (every skinned mesh / armor piece / outline, every frame) runs
 * {@code bindBufferFormat(format)} - 1 glBindBuffer + up to 9 glVertexAttrib(I)Pointer + 9 glEnableVertexAttribArray +
 * glBindBuffer(0) - and at the end {@code format.clearBufferState()} - up to 15 glDisableVertexAttribArray. All of
 * that state lives in the mesh's PRIVATE vertex array object ({@code arrayObjectId}, bound around draw() by
 * drawWithShader and never bound by anything else), the source buffers (outVertexAttrBO / midUVBO) are final
 * for the lifetime of the setup, and the vertex format only changes when the render type's shader changes. So the
 * VAO keeps exactly the state the previous draw left - re-specifying it is pure driver overhead
 * (profile: bindBufferFormat ~0.13% + clearBufferState, of the 2.7% ComputeShaderSetup.draw).
 * <p>
 * Fix: remember which VertexFormat the VAO was last set up for. Same format -> skip bindBufferFormat and leave the
 * attributes enabled (skip clearBufferState). Different format -> first disable the previous format's attributes
 * (exactly what the original end-of-draw clearBufferState did), then bind the new one as before. The GL state seen by
 * glDrawArrays is identical; the leftover enabled arrays live only in Epic Fight's own VAO, which nothing else
 * draws with. GL_ARRAY_BUFFER is restored by drawWithShader as before.
 * Disable: mixin.epicfight.ComputeShaderSetupVaoStateMixin=false
 */
@Mixin(value = ComputeShaderSetup.class, remap = false)
public abstract class ComputeShaderSetupVaoStateMixin {
    /** VertexFormat whose attribute pointers are currently enabled in this setup's VAO, or null. */
    @Unique
    private VertexFormat flyaway$vaoFormat;

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lyesman/epicfight/client/renderer/shader/compute/ComputeShaderSetup;bindBufferFormat(Lcom/mojang/blaze3d/vertex/VertexFormat;)V"))
    private void flyaway$bindOnce(ComputeShaderSetup self, VertexFormat format, Operation<Void> original) {
        VertexFormat current = this.flyaway$vaoFormat;
        if (current == format) return;
        if (current != null) {
            // same as the original clearBufferState() at the end of the previous draw (our VAO is bound here)
            current.clearBufferState();
        }
        original.call(self, format);
        this.flyaway$vaoFormat = format;
    }

    @WrapOperation(method = "draw", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/VertexFormat;clearBufferState()V"))
    private void flyaway$keepEnabled(VertexFormat format, Operation<Void> original) {
        if (this.flyaway$vaoFormat != format) {
            // not the state we track (should not happen) - behave exactly like the original
            original.call(format);
        }
    }
}
