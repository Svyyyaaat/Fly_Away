package dev.flyaway.perf.mixin.imm_ptl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.imm_ptl.ImmPtlTunables;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.lwjgl.opengl.GL15;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.portal.PortalRenderInfo;
import qouteall.imm_ptl.core.render.GlQueryObject;

/**
 * ON BY DEFAULT (imm_ptl.occlusion.noSyncWhenHidden=false restores stock behaviour). The user's 2026-10-03 profile
 * spent 15.2% of the render thread blocked in exactly this read; the 2026-10-11 one 1.3%.
 *
 * <p>Profile: {@code PortalRenderInfo.renderAndDecideVisibility -> GlQueryObject.fetchQueryResult ->
 * glGetQueryObjectiv} 1.27% of the render thread. micimmptl already made the LAST-frame query read non-blocking
 * (ordinal 0, {@code PortalRenderInfoNonBlockingQueryMixin}); the remaining stall is IP's own
 * "fetch_this_frame" branch (ordinal 1): when last frame said "hidden" and {@code noPredict} holds - which is
 * always the case for the first 4 queries of every frame ({@code QueryManager.queryStallCounter <= 3}, reset each
 * frame) and forever after 5 mispredictions - IP reads the query it has JUST issued, forcing the CPU to wait until
 * the GPU has finished the whole frame so far. This happens every frame for every portal that is in the frustum but
 * occluded.
 *
 * <p>With the tunable on, that branch answers "still hidden" (the previous frame's result) instead of stalling. The
 * query was still issued, so the next frame sees the fresh result: a portal emerging from behind an occluder is drawn
 * one frame late (the same latency IP already accepts once its stall budget is used up). That one frame is
 * the only (tiny) visual difference. The skip only happens when last frame's "hidden" was freshly read (result
 * available); when micimmptl served a stale cached answer we fall back to IP's original read (review finding). Ordinal 2 (no previous query at all) is left alone.
 */
@Mixin(value = PortalRenderInfo.class, remap = false)
public abstract class PortalRenderInfoNoSyncWhenHiddenMixin {
    /**
     * Sample GL_QUERY_RESULT_AVAILABLE for last frame's query BEFORE micimmptl's ordinal-0 wrapper reads it.
     * Availability only goes false -> true, so "available here" guarantees micimmptl read a fresh result
     * (sampling after its call could race: GPU finishes in between while micimmptl already served a stale cache).
     */
    @Inject(method = "renderAndDecideVisibility",
            at = @At(value = "INVOKE", target = "Lqouteall/imm_ptl/core/render/GlQueryObject;fetchQueryResult()Z", ordinal = 0))
    private static void flyaway$sampleLastAvailable(CallbackInfoReturnable<Boolean> cir,
            @Local(ordinal = 0) GlQueryObject lastFrameQuery, @Share("lastFresh") LocalBooleanRef lastFresh) {
        boolean fresh = false;
        if (ImmPtlTunables.OCCLUSION_NO_SYNC_WHEN_HIDDEN && lastFrameQuery != null) {
            int id = ((GlQueryObjectIdAccessor) lastFrameQuery).flyaway$id();
            fresh = id > 0 && GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) != 0;
        }
        lastFresh.set(fresh);
    }

    @WrapOperation(
            method = "renderAndDecideVisibility",
            at = @At(value = "INVOKE", target = "Lqouteall/imm_ptl/core/render/GlQueryObject;fetchQueryResult()Z", ordinal = 1))
    private static boolean flyaway$keepHiddenWithoutStall(GlQueryObject thisFrameQuery, Operation<Boolean> original,
            @Share("lastFresh") LocalBooleanRef lastFresh) {
        // Only trust "hidden" when last frame's query result was really readable when micimmptl/IP read it;
        // a stale cached answer falls back to IP's original (stalling) read so correctness holds.
        if (ImmPtlTunables.OCCLUSION_NO_SYNC_WHEN_HIDDEN && lastFresh.get()) {
            return false;
        }
        return original.call(thisFrameQuery);
    }
}
