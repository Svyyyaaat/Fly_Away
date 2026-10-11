package dev.flyaway.perf.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.flyaway.perf.iris.IrisFboDeferral;
import net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource;
import net.irisshaders.batchedentityrendering.impl.TransparencyType;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Opens the {@link IrisFboDeferral} scope around Iris' batched entity draw loops (main view, shadow pass, hand,
 * every Immersive Portals pass). Inside, only RenderType setup/draw/clear of already-buffered geometry runs, all
 * through GlStateManager, which is what makes the lazy main-target rebind and the glUseProgram dedup exact.
 * The scope is closed in a finally block, which also executes any still-deferred bind.
 * Colorwheel @Redirects a call inside endBatchWithType; @WrapMethod composes with that.
 * Disable: mixin.iris.IrisBatchFboScopeMixin=false (turns off both GL dedups).
 */
@Mixin(value = FullyBufferedMultiBufferSource.class, remap = false)
public abstract class IrisBatchFboScopeMixin {
    @WrapMethod(method = "endBatch()V")
    private void flyaway$scopeEndBatch(Operation<Void> original) {
        IrisFboDeferral.enter();
        try {
            original.call();
        } finally {
            IrisFboDeferral.exit();
        }
    }

    @WrapMethod(method = "endBatchWithType(Lnet/irisshaders/batchedentityrendering/impl/TransparencyType;)V")
    private void flyaway$scopeEndBatchWithType(TransparencyType type, Operation<Void> original) {
        IrisFboDeferral.enter();
        try {
            original.call(type);
        } finally {
            IrisFboDeferral.exit();
        }
    }
}
