package dev.flyaway.perf.mixin.imm_ptl;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;

/**
 * Fast path for {@code ImmPtlClientChunkMap.getChunk(int,int,ChunkStatus,boolean)}.
 *
 * <p>Profile: 1.74% of the render thread (almost all of it "self" at an injected line-0 return, i.e. the cost of
 * the call itself). Every {@code Level.getChunk} on a client level lands here (biome lookups from
 * {@code ClientLevel.animateTick}, {@code getBlockState}, fog colour...). Stock Immersive Portals allocates a
 * capturing lambda per call and goes through {@code readChunkMap}; make-it-compatible (micimmptl) adds two
 * cancellable {@code @Inject HEAD} handlers (plot-chunk redirect + a 4-slot MRU cache), each building a
 * {@code CallbackInfoReturnable}.
 *
 * <p>This {@code @WrapMethod} sits outside all of those. On the client main thread, for ordinary coordinates
 * (|x|,|z| &lt;= 62500 - beyond that micimmptl routes Sable plot chunks, so we defer to it), a hit in
 * {@code chunkMapForMainThread} is returned directly. That is exactly what both the stock body and micimmptl's
 * MRU cache return for a present chunk (the MRU cache is invalidated on every map modification, so it can never
 * disagree with the map). Misses (absent chunk -> null / emptyChunk), other threads and plot coordinates call the
 * original chain unchanged.
 */
@Mixin(value = ImmPtlClientChunkMap.class, remap = false)
public abstract class ImmPtlClientChunkMapFastGetMixin {
    @Shadow @Final protected Long2ObjectOpenHashMap<LevelChunk> chunkMapForMainThread;
    @Shadow @Final public Thread mainThread;

    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/LevelChunk;")
    private LevelChunk flyaway$fastGetChunk(int x, int z, ChunkStatus status, boolean create, Operation<LevelChunk> original) {
        if (x <= 62500 && x >= -62500 && z <= 62500 && z >= -62500 && Thread.currentThread() == this.mainThread) {
            LevelChunk chunk = this.chunkMapForMainThread.get(ChunkPos.asLong(x, z));
            if (chunk != null) {
                return chunk;
            }
        }
        return original.call(x, z, status, create);
    }
}
