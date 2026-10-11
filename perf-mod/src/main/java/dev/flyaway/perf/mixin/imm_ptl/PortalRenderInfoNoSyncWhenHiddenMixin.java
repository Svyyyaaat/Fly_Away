package dev.flyaway.perf.mixin.imm_ptl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.imm_ptl.ImmPtlTunables;
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
 * the only (tiny) visual difference. Ordinal 2 (no previous query at all) is left alone.
 */
@Mixin(value = PortalRenderInfo.class, remap = false, priority = 900) // before micimmptl (ordinal 0 wrap), so ordinal 1 is the original this-frame fetch
public abstract class PortalRenderInfoNoSyncWhenHiddenMixin {
    @WrapOperation(
            method = "renderAndDecideVisibility",
            at = @At(value = "INVOKE", target = "Lqouteall/imm_ptl/core/render/GlQueryObject;fetchQueryResult()Z", ordinal = 1))
    private static boolean flyaway$keepHiddenWithoutStall(GlQueryObject thisFrameQuery, Operation<Boolean> original) {
        if (ImmPtlTunables.OCCLUSION_NO_SYNC_WHEN_HIDDEN) {
            return false;
        }
        return original.call(thisFrameQuery);
    }
}
