package dev.flyaway.perf.vanilla.clienttick;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Per-ClientLevel chunk memo used only for the biome lookups of ClientLevel.doAnimateTick (ambient biome particles).
 *
 * Vanilla's getBiome(pos) -> BiomeManager.getBiome -> LevelReader.getNoiseBiome -> level.getChunk(cx, cz, BIOMES, false)
 * goes through Immersive Portals' ImmPtlClientChunkMap (lock + map lookup) for each of the ~1334 random positions per
 * tick, although they all fall into the ~25 chunks around the player. This source replicates
 * LevelReader.getNoiseBiome exactly, but remembers the chunk (or its absence) per chunk position until the next
 * animateTick() starts ({@link #reset()}). Chunks are only (un)loaded by packet handling on the render thread, never
 * during animateTick, so the memo returns the very same chunk objects vanilla would.
 */
public final class AnimateTickBiomeCache implements BiomeManager.NoiseBiomeSource {
    private static final Object NO_CHUNK = new Object();

    private final ClientLevel level;
    private final Long2ObjectOpenHashMap<Object> chunks = new Long2ObjectOpenHashMap<>(64);
    private BiomeManager manager;
    private BiomeManager managerBase;
    private long gameTime = Long.MIN_VALUE;

    public AnimateTickBiomeCache(ClientLevel level) {
        this.level = level;
    }

    /** Called at the start of every ClientLevel.animateTick. */
    public void reset() {
        if (!this.chunks.isEmpty()) this.chunks.clear();
        this.gameTime = this.level.getGameTime();
    }

    public Holder<Biome> getBiome(BlockPos pos) {
        // Belt and braces: a doAnimateTick caller outside animateTick in a later tick must not see old chunks.
        if (this.level.getGameTime() != this.gameTime) reset();
        BiomeManager base = this.level.getBiomeManager();
        if (this.managerBase != base) { // same seed/zoom as the level's manager, only the noise source differs
            this.managerBase = base;
            this.manager = base.withDifferentSource(this);
        }
        return this.manager.getBiome(pos);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        int cx = QuartPos.toSection(x);
        int cz = QuartPos.toSection(z);
        long key = ChunkPos.asLong(cx, cz);
        Object cached = this.chunks.get(key);
        if (cached == null) {
            ChunkAccess chunk = this.level.getChunk(cx, cz, ChunkStatus.BIOMES, false);
            cached = chunk != null ? chunk : NO_CHUNK;
            this.chunks.put(key, cached);
        }
        return cached != NO_CHUNK ? ((ChunkAccess) cached).getNoiseBiome(x, y, z) : this.level.getUncachedNoiseBiome(x, y, z);
    }
}
