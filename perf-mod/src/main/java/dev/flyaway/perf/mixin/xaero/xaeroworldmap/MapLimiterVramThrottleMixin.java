package dev.flyaway.perf.mixin.xaero.xaeroworldmap;

import dev.flyaway.perf.PerfConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapLimiter;

/**
 * Xaero's World Map calls {@code MapLimiter.updateAvailableVRAM()} at the end of EVERY
 * {@code MapProcessor.onRenderProcess} (i.e. every frame, RenderFrameEvent.Post). On NVIDIA/AMD it issues
 * {@code glGetIntegerv(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX / GL_TEXTURE_FREE_MEMORY_ATI)}: a driver query
 * that synchronises with the driver's worker thread (threaded optimisation) - 0.17% of the render thread in the
 * user's profile, all of it inside nglGetIntegerv.
 *
 * The value is only read by {@code MapLimiter.applyLimit} (region unloading when free VRAM drops below 200 MB),
 * which itself runs periodically from the map processor thread. Free VRAM changes slowly, so re-querying it at most
 * every {@code xaero.vramQueryIntervalMs} (default 1000 ms) is invisible; the limiter just sees a value up to 1 s old.
 * The very first call (driver type detection) always goes through.
 */
@Mixin(value = MapLimiter.class, remap = false)
public abstract class MapLimiterVramThrottleMixin {
    @Unique
    private static final long flyaway$INTERVAL_NS =
            Math.max(0, PerfConfig.getInt("xaero.vramQueryIntervalMs", 1000)) * 1_000_000L;

    @Shadow
    private int driverType;

    @Unique
    private long flyaway$lastQueryNs;

    @Inject(method = "updateAvailableVRAM", at = @At("HEAD"), cancellable = true)
    private void flyaway$throttleVramQuery(CallbackInfo ci) {
        if (this.driverType == -1) return; // first call: let xaero detect the driver type
        long now = System.nanoTime();
        if (this.flyaway$lastQueryNs != 0L && now - this.flyaway$lastQueryNs < flyaway$INTERVAL_NS) {
            ci.cancel(); // keep the previous availableVRAM value
            return;
        }
        this.flyaway$lastQueryNs = now;
    }
}
