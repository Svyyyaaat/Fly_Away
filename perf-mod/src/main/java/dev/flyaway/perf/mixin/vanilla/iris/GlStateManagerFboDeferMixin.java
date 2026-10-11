package dev.flyaway.perf.mixin.vanilla.iris;

import com.mojang.blaze3d.platform.GlStateManager;
import dev.flyaway.perf.iris.IrisFboDeferral;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * GL side of the Iris lazy main-target rebind ({@link IrisFboDeferral}; the scope is opened by
 * mixin.iris.IrisBatchFboScopeMixin, the deferral requested by mixin.iris.ExtendedShaderLazyRebindMixin).
 *
 * - _glBindFramebuffer: records the deferred bind / drops it when superseded / skips re-binding the FBO that is
 *   still physically bound.
 * - Every other GlStateManager entry point whose effect depends on the current framebuffer binding first
 *   executes a deferred bind, so it sees exactly the binding unpatched Iris would have had.
 * Outside an Iris batch scope every hook is a single static-field check (pass-through).
 *
 * Profile: glBindFramebuffer 8.39% self of the render thread; ~1.6% comes from the ExtendedShader.clear/apply
 * ping-pong inside FullyBufferedMultiBufferSource.endBatch.
 * Disable: mixin.vanilla.GlStateManagerFboDeferMixin=false (or mixin.iris.ExtendedShaderLazyRebindMixin=false).
 */
@Mixin(value = GlStateManager.class, remap = false)
public abstract class GlStateManagerFboDeferMixin {
    @Inject(method = "_glBindFramebuffer(II)V", at = @At("HEAD"), cancellable = true)
    private static void flyaway$deferBind(int target, int framebuffer, CallbackInfo ci) {
        if (IrisFboDeferral.depth != 0 && IrisFboDeferral.onBind(target, framebuffer)) ci.cancel();
    }

    @Inject(method = "_glDeleteFramebuffers(I)V", at = @At("HEAD"))
    private static void flyaway$beforeDelete(int framebuffer, CallbackInfo ci) {
        IrisFboDeferral.onDelete(framebuffer);
    }

    // ---- operations that observe the framebuffer binding: materialise a deferred bind first ----

    @Inject(method = "_drawElements(IIIJ)V", at = @At("HEAD"))
    private static void flyaway$flushDraw(int mode, int count, int type, long indices, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_clear(IZ)V", at = @At("HEAD"))
    private static void flyaway$flushClear(int mask, boolean checkError, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_glBlitFrameBuffer(IIIIIIIIII)V", at = @At("HEAD"))
    private static void flyaway$flushBlit(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_readPixels(IIIIIILjava/nio/ByteBuffer;)V", at = @At("HEAD"))
    private static void flyaway$flushRead1(int x, int y, int w, int h, int format, int type, ByteBuffer buf, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_readPixels(IIIIIIJ)V", at = @At("HEAD"))
    private static void flyaway$flushRead2(int x, int y, int w, int h, int format, int type, long ptr, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_glCopyTexSubImage2D(IIIIIIII)V", at = @At("HEAD"))
    private static void flyaway$flushCopyTex(int a, int b, int c, int d, int e, int f, int g, int h, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_glDrawPixels(IIIIJ)V", at = @At("HEAD"))
    private static void flyaway$flushDrawPixels(int w, int h, int format, int type, long ptr, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_glFramebufferTexture2D(IIIII)V", at = @At("HEAD"))
    private static void flyaway$flushAttachTex(int target, int attachment, int texTarget, int tex, int level, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_glFramebufferRenderbuffer(IIII)V", at = @At("HEAD"))
    private static void flyaway$flushAttachRb(int target, int attachment, int rbTarget, int rb, CallbackInfo ci) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "glCheckFramebufferStatus(I)I", at = @At("HEAD"))
    private static void flyaway$flushStatus(int target, CallbackInfoReturnable<Integer> cir) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "getBoundFramebuffer()I", at = @At("HEAD"))
    private static void flyaway$flushGetBound(CallbackInfoReturnable<Integer> cir) {
        IrisFboDeferral.flush();
    }

    @Inject(method = "_getInteger(I)I", at = @At("HEAD"))
    private static void flyaway$flushGetInteger(int pname, CallbackInfoReturnable<Integer> cir) {
        IrisFboDeferral.flush();
    }
}
