package dev.flyaway.perf.mixin.xaero.xaeroworldmap;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.xaero.HighlightHashVersioned;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import xaero.map.highlight.DimensionHighlighterHandler;
import xaero.map.region.MapRegion;

/**
 * Part 2 of the region highlight-hash memo (part 1: {@link DimensionHighlighterHandlerVersionMixin}).
 *
 * MapProcessor.onRenderProcess runs every frame and calls MapRegion.processWhenLoadedChunksExist for every region in
 * its to-process lists that has loaded textures; that method calls
 * DimensionHighlighterHandler.getRegionHash(regionX, regionZ) = monitorenter + Long2ObjectOpenHashMap.get + unboxing,
 * just to compare against the region's stored highlights hash. Profile: Long2ObjectOpenHashMap.get 0.29% self, all
 * from this one call site.
 *
 * Fix: each region remembers (handler version, hash) from its last call; while the handler's version is unchanged the
 * map entry cannot have changed (entries are only removed by clearCachedHash/clearCachedHashes, which bump the
 * version after removing), so the remembered hash is returned without touching the map. The version is read BEFORE
 * the real lookup, so a concurrent clear can only cause one extra (correct) lookup next frame, never a stale result.
 * Result is bit-identical to vanilla xaero.
 */
@Mixin(value = MapRegion.class, remap = false)
public abstract class MapRegionHighlightHashMixin {
    /** (version << 32) | hash of the last real lookup; -1 = nothing remembered (version -1 is never reached). */
    @Unique
    private volatile long flyaway$highlightHashMemo = -1L;

    @WrapOperation(
            method = "processWhenLoadedChunksExist",
            at = @At(value = "INVOKE", target = "Lxaero/map/highlight/DimensionHighlighterHandler;getRegionHash(II)I"))
    private int flyaway$memoRegionHash(DimensionHighlighterHandler handler, int regionX, int regionZ, Operation<Integer> original) {
        if (!(handler instanceof HighlightHashVersioned versioned)) {
            return original.call(handler, regionX, regionZ);
        }
        int version = versioned.flyaway$hashVersion();
        long memo = this.flyaway$highlightHashMemo;
        if (memo != -1L && (int) (memo >>> 32) == version) {
            return (int) memo;
        }
        int hash = original.call(handler, regionX, regionZ);
        this.flyaway$highlightHashMemo = ((long) version << 32) | (hash & 0xFFFFFFFFL);
        return hash;
    }
}
