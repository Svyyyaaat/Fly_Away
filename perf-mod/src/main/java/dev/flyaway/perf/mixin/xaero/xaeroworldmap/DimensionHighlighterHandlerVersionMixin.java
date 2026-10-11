package dev.flyaway.perf.mixin.xaero.xaeroworldmap;

import dev.flyaway.perf.xaero.HighlightHashVersioned;
import java.util.concurrent.atomic.AtomicInteger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.highlight.DimensionHighlighterHandler;

/**
 * Part 1 of the region highlight-hash memo (part 2: {@link MapRegionHighlightHashMixin}).
 * DimensionHighlighterHandler.hashCodeCache (Long2ObjectOpenHashMap&lt;Integer&gt;, guarded by synchronized(this)) only
 * ever loses entries in clearCachedHash(x, z) / clearCachedHashes(); a present entry never changes value and a missing
 * entry is recomputed deterministically. We bump a version counter AFTER every such removal, so a reader that
 * remembered (version, hash) and still sees the same version knows getRegionHash would return the same hash.
 */
@Mixin(value = DimensionHighlighterHandler.class, remap = false)
public abstract class DimensionHighlighterHandlerVersionMixin implements HighlightHashVersioned {
    @Unique
    private final AtomicInteger flyaway$version = new AtomicInteger();

    @Override
    public int flyaway$hashVersion() {
        return this.flyaway$version.get();
    }

    @Inject(method = "clearCachedHash", at = @At("RETURN"))
    private void flyaway$bumpOnClear(int regionX, int regionZ, CallbackInfo ci) {
        this.flyaway$version.incrementAndGet();
    }

    @Inject(method = "clearCachedHashes", at = @At("RETURN"))
    private void flyaway$bumpOnClearAll(CallbackInfo ci) {
        this.flyaway$version.incrementAndGet();
    }
}
