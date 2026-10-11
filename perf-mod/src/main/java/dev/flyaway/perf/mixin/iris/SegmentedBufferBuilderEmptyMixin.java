package dev.flyaway.perf.mixin.iris;

import com.mojang.blaze3d.vertex.BufferBuilder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.irisshaders.batchedentityrendering.impl.BufferSegment;
import net.irisshaders.batchedentityrendering.impl.SegmentedBufferBuilder;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iris batched entity rendering: FullyBufferedMultiBufferSource.readyUp() calls getSegments() on all 32
 * SegmentedBufferBuilders for every batch (main view, shadow pass, hand, every Immersive Portals pass), and each
 * call allocates a capturing lambda plus a copied ArrayList even when the builder received no geometry (most of
 * the 32 do not: e.g. the shadow pass only draws the player). Profile: ArrayList.&lt;init&gt; in getSegments 0.22% self.
 *
 * Fix: when the builder holds no BufferBuilder and no pending segment, return the shared empty list. The only caller
 * (readyUp) just iterates the result, so this is exactly equivalent.
 * Expected: ~0.1-0.2%. Risk: none. Disable: mixin.iris.SegmentedBufferBuilderEmptyMixin=false.
 */
@Mixin(value = SegmentedBufferBuilder.class, remap = false)
public abstract class SegmentedBufferBuilderEmptyMixin {
    @Shadow
    @Final
    private Map<RenderType, BufferBuilder> builders;

    @Shadow
    @Final
    private List<BufferSegment> segments;

    @Inject(method = "getSegments()Ljava/util/List;", at = @At("HEAD"), cancellable = true)
    private void flyaway$emptyFastPath(CallbackInfoReturnable<List<BufferSegment>> cir) {
        if (this.builders.isEmpty() && this.segments.isEmpty()) {
            cir.setReturnValue(Collections.emptyList());
        }
    }
}
