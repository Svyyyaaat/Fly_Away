package dev.flyaway.perf.mixin.vanilla.particles;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.vanilla.particles.AnimateTickBiomeCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ClientLevel.animateTick (client tick, render thread) calls doAnimateTick 1334 times per tick; every call whose block
 * is not a full collision cube asks getBiome(pos) for the biome's ambient particle (almost always empty). Each of those
 * lookups goes through Level.getChunk -> ImmPtlClientChunkMap.getChunk.
 * Profile: doAnimateTick 1.70% of the render thread, of which getBiome 1.01% (0.80% in the chunk-map lookup).
 *
 * <p>Fix: during one animateTick call the biome lookup uses {@link AnimateTickBiomeCache}, which memoises the ~25
 * distinct chunks touched. Same biome result (vanilla zoom code, same seed, same chunk/uncached fallback), same random
 * consumption, same particles. Outside animateTick (e.g. other callers of doAnimateTick) the original call runs.
 * Expected: most of the 0.8% chunk-lookup share. Disable: mixin.vanilla.ClientLevelAnimateTickBiomeMixin=false
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelAnimateTickBiomeMixin {
    @Unique
    private AnimateTickBiomeCache flyaway$biomeCache;

    @Inject(method = "animateTick(III)V", at = @At("HEAD"))
    private void flyaway$beginBiomeCache(int x, int y, int z, CallbackInfo ci) {
        AnimateTickBiomeCache cache = this.flyaway$biomeCache;
        if (cache == null) cache = this.flyaway$biomeCache = new AnimateTickBiomeCache((ClientLevel) (Object) this);
        cache.begin();
    }

    @Inject(method = "animateTick(III)V", at = @At("RETURN"))
    private void flyaway$endBiomeCache(int x, int y, int z, CallbackInfo ci) {
        if (this.flyaway$biomeCache != null) this.flyaway$biomeCache.end();
    }

    @WrapOperation(method = "doAnimateTick(IIIILnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/core/BlockPos$MutableBlockPos;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> flyaway$cachedBiome(ClientLevel level, BlockPos pos, Operation<Holder<Biome>> original) {
        AnimateTickBiomeCache cache = this.flyaway$biomeCache;
        if (cache == null || !cache.isActive()) return original.call(level, pos);
        return cache.getBiome(pos);
    }
}
