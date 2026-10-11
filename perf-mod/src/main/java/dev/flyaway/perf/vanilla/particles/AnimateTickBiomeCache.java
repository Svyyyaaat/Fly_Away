package dev.flyaway.perf.vanilla.particles;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Chunk lookup cache used only for the ambient-particle biome lookup of ClientLevel.doAnimateTick, and only while a
 * single ClientLevel.animateTick call runs (render thread; chunks cannot be loaded or unloaded in that window because
 * that only happens while processing packets on the same thread).
 *
 * <p>animateTick samples 2 x 667 random positions within +-32 blocks of the player, i.e. ~25 distinct chunks, and each
 * sample's getBiome() does a chunk-map lookup (ImmPtlClientChunkMap.getChunk: lambda + thread check + hash lookup).
 * This replaces ~1334 lookups per tick with ~25. {@link #getNoiseBiome} is a copy of
 * LevelReader#getNoiseBiome (getChunk(BIOMES, false), else getUncachedNoiseBiome) with the chunk memoised, and the
 * biome zoom (BiomeManager#getBiome fuzzing) is the vanilla code via withDifferentSource(), so results are identical.
 */
public final class AnimateTickBiomeCache implements BiomeManager.NoiseBiomeSource {
    private static final int SIZE = 64; // power of two, > max distinct chunks per animateTick (5x5 + sable plots)
    private static final int MAX_FILL = 48;

    private final ClientLevel level;
    private final long[] keys = new long[SIZE];
    private final ChunkAccess[] chunks = new ChunkAccess[SIZE];
    private final boolean[] used = new boolean[SIZE];
    private int filled;
    private BiomeManager manager;
    private boolean active;

    public AnimateTickBiomeCache(ClientLevel level) {
        this.level = level;
    }

    public void begin() {
        clear();
        this.active = true;
    }

    public void end() {
        this.active = false;
        clear(); // do not retain chunk references between ticks
    }

    public boolean isActive() {
        return this.active;
    }

    /** Vanilla-equivalent of level.getBiome(pos) with memoised chunk lookups. */
    public Holder<Biome> getBiome(net.minecraft.core.BlockPos pos) {
        BiomeManager m = this.manager;
        if (m == null) {
            // Same zoom seed as the level's own BiomeManager, only the noise source differs.
            m = this.manager = this.level.getBiomeManager().withDifferentSource(this);
        }
        return m.getBiome(pos);
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz) {
        int cx = QuartPos.toSection(qx);
        int cz = QuartPos.toSection(qz);
        ChunkAccess chunk = lookup(cx, cz);
        return chunk != null ? chunk.getNoiseBiome(qx, qy, qz) : this.level.getUncachedNoiseBiome(qx, qy, qz);
    }

    private ChunkAccess lookup(int cx, int cz) {
        long key = ChunkPos.asLong(cx, cz);
        int idx = (int) (key ^ (key >>> 29) ^ (key >>> 37)) * 0x9E3779B9 >>> 26; // 6 bits
        for (int probe = 0; probe < SIZE; probe++) {
            int i = (idx + probe) & (SIZE - 1);
            if (!this.used[i]) {
                ChunkAccess chunk = this.level.getChunk(cx, cz, ChunkStatus.BIOMES, false);
                if (this.filled >= MAX_FILL) clear(); // overflow: start over (still correct)
                else {
                    this.used[i] = true;
                    this.keys[i] = key;
                    this.chunks[i] = chunk;
                    this.filled++;
                }
                return chunk;
            }
            if (this.keys[i] == key) return this.chunks[i];
        }
        return this.level.getChunk(cx, cz, ChunkStatus.BIOMES, false);
    }

    private void clear() {
        if (this.filled == 0) return;
        java.util.Arrays.fill(this.used, false);
        java.util.Arrays.fill(this.chunks, null);
        this.filled = 0;
    }
}
