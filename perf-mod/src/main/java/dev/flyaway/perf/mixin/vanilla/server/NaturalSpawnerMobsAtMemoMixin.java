package dev.flyaway.perf.mixin.vanilla.server;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.vanilla.server.SpawnerLookupCache;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Natural spawning: do not compute the same {@code mobsAt} list twice in a row.
 *
 * <p>For the first attempt of every spawn group, spawnCategoryForPosition calls getRandomSpawnMobAt(pos) ->
 * {@code mobsAt(level, sm, gen, category, pos, level.getBiome(pos))} and then, with only RNG calls in between,
 * isValidSpawnPostitionForType(pos) -> canSpawnMobAt -> {@code mobsAt(level, sm, gen, category, pos, null)}, which
 * recomputes the identical list: biome lookup, NeoForge nether-fortress check (YUNG's Better Fortresses adds a
 * registry + structure lookup there), ChunkGenerator.getMobsAt (structure-reference scan of the chunk and
 * piece/bounding-box tests for every structure with spawn overrides - many in this pack: ~40 structure mods, plus
 * Moog's Structure Lib HEAD hook) and the LevelEvent.PotentialSpawns post. Profile: canSpawnMobAt -> mobsAt 0.58% of
 * the server thread; the group-first share of it is redundant.
 *
 * <p>Fix: the list from getRandomSpawnMobAt is remembered (single use) and returned by the next canSpawnMobAt if
 * level, category and block position are identical; any other canSpawnMobAt drops it, the next getRandomSpawnMobAt
 * replaces it, and it never leaves the spawnCategoryForPosition call (scope in {@link SpawnerLookupCache}). Between
 * the two calls nothing touches the world (a mob is only created after canSpawnMobAt returned), so the result is the
 * same object vanilla would have rebuilt. No mod in this pack listens to PotentialSpawns (checked: only Puzzles Lib's
 * unused adapter), so the event is not observably skipped.
 *
 * <p>Expected saving ~0.2-0.3% of the server thread. Disable: {@code mixin.vanilla.NaturalSpawnerMobsAtMemoMixin=false}.
 */
@Mixin(NaturalSpawner.class)
public abstract class NaturalSpawnerMobsAtMemoMixin {
    @WrapMethod(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V")
    private static void flyaway$mobsScope(MobCategory category, ServerLevel level, ChunkAccess chunk, BlockPos pos,
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
            method = "getRandomSpawnMobAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Ljava/util/Optional;",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;mobsAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)Lnet/minecraft/util/random/WeightedRandomList;"))
    private static WeightedRandomList<MobSpawnSettings.SpawnerData> flyaway$rememberMobs(
            ServerLevel level, StructureManager structures, ChunkGenerator generator, MobCategory category, BlockPos pos,
            Holder<Biome> biome, Operation<WeightedRandomList<MobSpawnSettings.SpawnerData>> original) {
        WeightedRandomList<MobSpawnSettings.SpawnerData> list = original.call(level, structures, generator, category, pos, biome);
        SpawnerLookupCache.rememberMobs(level, category, pos, list);
        return list;
    }

    @WrapOperation(
            method = "canSpawnMobAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/world/level/biome/MobSpawnSettings$SpawnerData;Lnet/minecraft/core/BlockPos;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/NaturalSpawner;mobsAt(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/world/entity/MobCategory;Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/Holder;)Lnet/minecraft/util/random/WeightedRandomList;"))
    private static WeightedRandomList<MobSpawnSettings.SpawnerData> flyaway$reuseMobs(
            ServerLevel level, StructureManager structures, ChunkGenerator generator, MobCategory category, BlockPos pos,
            Holder<Biome> biome, Operation<WeightedRandomList<MobSpawnSettings.SpawnerData>> original) {
        WeightedRandomList<MobSpawnSettings.SpawnerData> list = SpawnerLookupCache.takeMobs(level, category, pos);
        return list != null ? list : original.call(level, structures, generator, category, pos, biome);
    }
}
