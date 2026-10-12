package dev.flyaway.perf.mixin.xaero.xaeroworldmap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.MapProcessor;
import xaero.map.cache.BlockStateShortShapeCache;
import xaero.map.file.worldsave.WorldDataHandler;

/**
 * Portal-exit freeze (user's spark profile lA0sfAOol9: {@code MapProcessor.waitForLoadingToFinish} = 9.6% of the
 * render thread, every Nether -> Overworld exit followed by a 200-850 ms frame right at XaeroPlus'
 * VIEWED_DIMENSION_SWITCH).
 *
 * On a dimension change {@code MapProcessor.changeWorld} (render thread, from {@code checkForWorldUpdate}) busy-waits
 * in {@code waitForLoadingToFinish} until the map processor thread drops {@code isLoading}, and only THEN sets
 * {@code waitingForWorldUpdate = true}. But that flag is exactly what the processor's loading loops poll to bail
 * out early ({@code MapSaveLoad.run}: {@code while (... && !isWaitingForWorldUpdate() && ...)} over the whole
 * {@code toLoad} queue - cache-only loads never end the loop, so after a dimension switch it walks dozens of region
 * cache files). The render thread therefore always waits for the entire queue of the old dimension.
 *
 * <p>Fix: raise {@code waitingForWorldUpdate} before the wait, so the processor stops after the region it is on.
 * Safe because {@code changeWorld} is synchronized on the MapProcessor, and the only place that clears the flag
 * ({@code updateWorldSynced}) is synchronized on it too, so nothing can reset it while we wait; the regions left in
 * the queue are handled by the normal world-update path exactly as when the flag is set a moment later.
 *
 * <p>Also, the wait loop re-enters {@code loadingSync} back to back, which can starve the processor thread trying to
 * take the same monitor in {@code popIsLoading}; we yield between polls (same work done per iteration otherwise).
 */
@Mixin(value = MapProcessor.class, remap = false)
public abstract class MapProcessorWorldChangeWaitMixin {
    @Shadow
    private boolean waitingForWorldUpdate;

    @Shadow
    @Final
    private Object loadingSync;

    @Shadow
    private boolean isLoading;

    @Shadow
    @Final
    private BlockStateShortShapeCache blockStateShortShapeCache;

    @Shadow
    private WorldDataHandler worldDataHandler;

    @Inject(method = "changeWorld", at = @At(value = "INVOKE",
            target = "Lxaero/map/MapProcessor;waitForLoadingToFinish(Ljava/lang/Runnable;)V"))
    private void flyaway$stopLoadingEarly(CallbackInfo ci) {
        this.waitingForWorldUpdate = true;
    }

    @Inject(method = "waitForLoadingToFinish", at = @At("HEAD"), cancellable = true)
    private void flyaway$politeWait(Runnable onFinish, CallbackInfo ci) {
        while (true) {
            synchronized (this.loadingSync) {
                if (!this.isLoading) {
                    onFinish.run();
                    break;
                }
                this.blockStateShortShapeCache.supplyForIOThread();
                this.worldDataHandler.handleRenderExecutor();
            }
            Thread.yield();
        }
        ci.cancel();
    }
}
