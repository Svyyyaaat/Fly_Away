package dev.flyaway.perf.mixin.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.nio.FloatBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yesman.epicfight.client.renderer.shader.compute.backend.ssbo.DynamicSSBO;

/**
 * What: skip re-uploading Epic Fight's per-draw "hidden part flags" SSBO when its content is unchanged.
 * <p>
 * Why: with use_persistent_buffer=false (the pack's setting) every skinned-mesh draw takes the next of 32 pooled
 * hidden-flag SSBOs and orphans + re-uploads it with {@code glBufferData} (patched DynamicSSBO.updateFromTo) - one of the
 * two glBufferData calls per draw (profile: DynamicSSBO.updateFromTo 0.39% of the render thread, mostly the driver
 * call). The flags are 1-8 ints and almost always all zero (only first-person arms / custom hidden parts set bits),
 * so after the first lap through the pool every buffer already holds exactly the bytes about to be uploaded.
 * <p>
 * Fix: only for small element buffers (srcSize == 1, i.e. the hidden-flag pool; the 64-byte-per-element pose pool
 * changes every frame and is left alone) remember what was last uploaded into THIS buffer with the full-buffer
 * glBufferData; if the new data is byte-identical, skip the glBufferData. The buffer's GPU storage then still holds
 * those exact bytes (the compute shader only reads it: "readonly buffer PartHiddenFlagBuffer"), so every draw sees the
 * same data as before. Any partial (from != 0) update invalidates the memo.
 * Saving: ~half of the glBufferData calls of the compute path (~0.15-0.2% render thread).
 * Disable: mixin.epicfight.DynamicSSBOUploadDedupMixin=false
 */
@Mixin(value = DynamicSSBO.class, remap = false)
public abstract class DynamicSSBOUploadDedupMixin {
    @Unique
    private static final int FLYAWAY_MAX_FLOATS = 64;

    @Shadow
    @Final
    public short srcSize;

    @Unique
    private final int[] flyaway$last = new int[FLYAWAY_MAX_FLOATS];
    @Unique
    private int flyaway$lastLen = -1;

    @Inject(method = "updateFromTo", at = @At("HEAD"))
    private void flyaway$invalidateOnPartial(int from, int to, CallbackInfo ci) {
        if (from != 0) this.flyaway$lastLen = -1;
    }

    @WrapOperation(method = "updateFromTo", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL15C;glBufferData(ILjava/nio/FloatBuffer;I)V"))
    private void flyaway$skipIdentical(int target, FloatBuffer data, int usage, Operation<Void> original) {
        int pos = data.position();
        int n = data.limit() - pos;
        if (this.srcSize != 1 || n > FLYAWAY_MAX_FLOATS) {
            original.call(target, data, usage);
            return;
        }
        int[] last = this.flyaway$last;
        if (n == this.flyaway$lastLen) {
            boolean same = true;
            for (int i = 0; i < n; i++) {
                // compare raw bits: the flags are ints stored via Float.intBitsToFloat (may be NaN patterns)
                if (Float.floatToRawIntBits(data.get(pos + i)) != last[i]) {
                    same = false;
                    break;
                }
            }
            if (same) return;
        }
        original.call(target, data, usage);
        for (int i = 0; i < n; i++) last[i] = Float.floatToRawIntBits(data.get(pos + i));
        this.flyaway$lastLen = n;
    }
}
