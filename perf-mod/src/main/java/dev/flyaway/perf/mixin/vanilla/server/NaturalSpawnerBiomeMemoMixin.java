package dev.flyaway.perf.mixin.vanilla.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.vanilla.server.SpawnerLookupCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Natural spawning: memoise the chunk lookups behind the spawner's biome queries.
 *
 * <p>Profile (user's Server thread, 60 s): NaturalSpawner.getRandomSpawnMobAt 3.95% of the server thread, of which
 * LevelReader.getBiome 2.17% - 0.88% of it in Level.getChunk (ServerChunkCache main-thread getChunk, c2me's
 * instrumentGetChunk wrapper, getChunkFutureMainThread, flowsched holder map) and the same again (0.27%) inside
 * canSpawnMobAt -> mobsAt. Every spawn attempt resolves its chunk anew although all attempts of one
 * spawnCategoryForPosition call random-walk inside 1-4 chunks.
 *
 * <p>Fix: inside one spawnCategoryForPosition call the {@code ServerLevel.getBiome(pos)} calls of getRandomSpawnMobAt
 * and mobsAt go through {@link SpawnerLookupCache#biome}: the level's own BiomeManager (same zoom seed, same fuzz,
 * c2me's native sampler still applies) with a noise source that is an exact copy of LevelReader.getNoiseBiome but
 * remembers the full chunks it got from {@code getChunk(cx, cz, BIOMES, false)}. Same chunk objects, same holders,
 * same spawns. Outside the scope / off the server thread the vanilla call runs.
 *
 * <p>Expected saving: most of the ~1% (of the server thread) spent in the spawner's chunk lookups.
 *
 * <p>Priority 900: applied before ServerCore's optional {@code optimizations.biome_lookups} @Redirect on the same call
 * (require = 0 there, off in this pack). If a user turns that ServerCore option on, our exact lookup wins.
 * Disable: {@code mixin.vanilla.NaturalSpawnerBiomeMemoMixin=false}.
 */
@Mixin(value = NaturalSpawner.class, priority = 900)
public abstract class NaturalSpawnerBiomeMemoMixin {
    @WrapMethod(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V")
    private static void flyaway$biomeScope(MobCategory category, ServerLevel level, ChunkAccess chunk, BlockPos pos,
                                           NaturalSpawner.SpawnPredicate predicate, NaturalSpawner.AfterSpawnCallback callback,
                                           Operation<Void> original) {
        boolean began = SpawnerLookupCache.begin(level);
        try {
            original.call(category, level, chunk, pos, predicate, callback);
        } finally {
            if (began) SpawnerLookupCache.end();
        }
    }

    @WrapOperation(
            method = {
                    "getRandomSpawnMobAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;",
                    "mobsAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)Lnet/minecraft/util/random/WeightedRandomList;"
            },
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getBiome(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/Holder;"),
            require = 2)
    private static Holder<Biome> flyaway$memoBiome(ServerLevel level, BlockPos pos, Operation<Holder<Biome>> original) {
        Holder<Biome> biome = SpawnerLookupCache.biome(level, pos);
        return biome != null ? biome : original.call(level, pos);
    }
}
