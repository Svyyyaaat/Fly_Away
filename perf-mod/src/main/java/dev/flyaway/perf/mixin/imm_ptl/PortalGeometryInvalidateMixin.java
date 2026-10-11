package dev.flyaway.perf.mixin.imm_ptl;

import dev.flyaway.perf.imm_ptl.FramePortalQueryCache;
import dev.flyaway.perf.imm_ptl.PortalFrameScope;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Safety net for {@link FramePortalQueryCache}: every geometry change of a portal (position via
 * {@code ip_onEntityPositionUpdated}, orientation, size, destination, reload) goes through
 * {@code Portal.updateCache()}. Some portals do move during a frame - make-it-compatible re-poses portals mounted on
 * Sable vehicles at {@code RenderLevelStageEvent AFTER_SKY} - so any such change inside a frame drops the per-frame
 * portal-query cache. Cost: one thread compare per call (rare outside frames anyway).
 */
@Mixin(value = Portal.class, remap = false)
public abstract class PortalGeometryInvalidateMixin {
    @Inject(method = "updateCache()V", at = @At("HEAD"))
    private void flyaway$invalidateFramePortalQueries(CallbackInfo ci) {
        if (PortalFrameScope.isActiveOnThisThread()) {
            FramePortalQueryCache.clear();
        }
    }
}
