package dev.flyaway.perf.mixin.vanilla.clienttick;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.vanilla.clienttick.AnimateTickBiomeCache;
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
 * ClientLevel.animateTick runs 667 x 2 doAnimateTick() per client tick (plus Sable's extra calls for sub-levels at
 * animateTick TAIL). For every position that is not a full collision block, doAnimateTick asks getBiome(pos) for the
 * biome's ambient particle; each lookup walks Level.getChunk -> ImmPtlClientChunkMap.getChunk. Profile: getBiome
 * 1.01% of the render thread, 0.80% of it in the chunk lookup.
 *
 * Fix: the getBiome call inside doAnimateTick is served by a BiomeManager with the level's own zoom seed whose noise
 * source memoises chunk lookups until the next animateTick (see AnimateTickBiomeCache) - same chunk objects, same
 * biome holders, identical particles. Only used on the render thread (doAnimateTick is client-only anyway).
 * Expected saving ~0.7% of the render thread.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelAnimateTickBiomeMixin {
    @Unique
    private AnimateTickBiomeCache flyaway$animateBiomeCache;

    @Inject(method = "animateTick", at = @At("HEAD"))
    private void flyaway$resetBiomeCache(int x, int y, int z, CallbackInfo ci) {
        if (this.flyaway$animateBiomeCache != null) this.flyaway$animateBiomeCache.reset();
    }

    @WrapOperation(
            method = "doAnimateTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"))
    private Holder<Biome> flyaway$cachedBiome(ClientLevel level, BlockPos pos, Operation<Holder<Biome>> original) {
        AnimateTickBiomeCache cache = this.flyaway$animateBiomeCache;
        if (cache == null) {
            this.flyaway$animateBiomeCache = cache = new AnimateTickBiomeCache(level);
        }
        return cache.getBiome(pos);
    }
}
