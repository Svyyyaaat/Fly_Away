package dev.flyaway.perf.vanilla.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedRandomList;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Scratch state for ONE NaturalSpawner.spawnCategoryForPosition call on the server thread (the "scope").
 *
 * <p>Two exact memos live here, both empty outside the scope:
 * <ul>
 *   <li><b>chunk memo for biome lookups</b> - vanilla's {@code level.getBiome(pos)} inside the spawner ends in
 *   {@code LevelReader.getNoiseBiome -> level.getChunk(cx, cz, BIOMES, false)} (ServerChunkCache main-thread path,
 *   c2me's instrumented wrapper, a holder-map lookup) for every spawn attempt, although all attempts of one call
 *   random-walk within ~+-15 blocks of one start position, i.e. 1-4 chunks. This class is a
 *   {@link BiomeManager.NoiseBiomeSource} that replicates {@code LevelReader.getNoiseBiome} exactly but remembers the
 *   full-chunk objects that {@code getChunk(.., BIOMES, false)} returned. Absent and proto chunks are NOT memoised
 *   (looked up again on every call exactly like vanilla), so a chunk that appears or is promoted mid-call is still
 *   seen.
 *   The zoom/fuzz of the position is done by the level's own BiomeManager ({@code withDifferentSource}, same zoom
 *   seed, c2me's native sampler still applies) - same quart positions, same chunk objects, same holders.</li>
 *   <li><b>mobsAt pair memo</b> - for the first attempt of every spawn group vanilla computes
 *   {@code mobsAt(level, .., category, pos, biome)} in getRandomSpawnMobAt and, a few RNG calls later and with no
 *   world access in between, the identical {@code mobsAt(level, .., category, pos, null)} again in canSpawnMobAt
 *   (biome lookup + structure-start scan + nether-fortress check + PotentialSpawns event). The second call reuses the
 *   first result when level, category and block position match. The memo is single-use and is dropped by any
 *   non-matching canSpawnMobAt, by the next getRandomSpawnMobAt and when the scope ends, so it can never be served
 *   across a spawned mob or across calls.</li>
 * </ul>
 *
 * <p>Threading: only the owning server thread ever activates the scope ({@link #begin} checks
 * {@code server.isSameThread()}); every accessor re-checks the owner thread and level, otherwise callers take the
 * vanilla path. Nested/recursive spawner calls simply reset the scope (inner {@link #end} deactivates it, the outer
 * remainder then runs uncached).
 */
public final class SpawnerLookupCache implements BiomeManager.NoiseBiomeSource {
    private static final SpawnerLookupCache INSTANCE = new SpawnerLookupCache();
    private static final int CHUNK_SLOTS = 4;

    private Thread owner;
    private ServerLevel level;

    // --- biome chunk memo
    private BiomeManager managerBase;
    private BiomeManager manager;
    private final long[] chunkKeys = new long[CHUNK_SLOTS];
    private final ChunkAccess[] chunkRefs = new ChunkAccess[CHUNK_SLOTS];
    private int chunkCount;
    private int chunkNext;

    // --- mobsAt pair memo
    private boolean mobsValid;
    private MobCategory mobsCategory;
    private long mobsPos;
    private WeightedRandomList<MobSpawnSettings.SpawnerData> mobsList;

    private SpawnerLookupCache() {}

    /** Enter the scope for one spawnCategoryForPosition call. Returns true if the scope was activated. */
    public static boolean begin(ServerLevel level) {
        if (level == null || !level.getServer().isSameThread()) return false;
        SpawnerLookupCache c = INSTANCE;
        c.clear();
        c.owner = Thread.currentThread();
        c.level = level;
        return true;
    }

    /** Leave the scope: drop every reference (no chunk/level/list may outlive the call). */
    public static void end() {
        SpawnerLookupCache c = INSTANCE;
        if (c.owner == Thread.currentThread()) {
            c.clear();
            c.owner = null;
            c.level = null;
        }
    }

    private void clear() {
        for (int i = 0; i < this.chunkCount; i++) this.chunkRefs[i] = null;
        this.chunkCount = 0;
        this.chunkNext = 0;
        this.managerBase = null;
        this.manager = null;
        this.dropMobs();
    }

    private void dropMobs() {
        this.mobsValid = false;
        this.mobsCategory = null;
        this.mobsList = null;
    }

    private static SpawnerLookupCache active(ServerLevel level) {
        SpawnerLookupCache c = INSTANCE;
        return c.owner == Thread.currentThread() && c.level == level ? c : null;
    }

    // ------------------------------------------------------------------ biome

    /** Returns the biome at pos exactly like level.getBiome(pos), or null if the scope is not active for this level. */
    public static Holder<Biome> biome(ServerLevel level, BlockPos pos) {
        SpawnerLookupCache c = active(level);
        if (c == null) return null;
        BiomeManager base = level.getBiomeManager();
        if (c.managerBase != base) {
            c.managerBase = base;
            c.manager = base.withDifferentSource(c);
        }
        return c.manager.getBiome(pos);
    }

    /** Exact copy of LevelReader.getNoiseBiome, with the BIOMES-status chunk lookup memoised for this scope. */
    @Override
    public Holder<Biome> getNoiseBiome(int x, int y, int z) {
        ServerLevel lvl = this.level;
        int cx = QuartPos.toSection(x);
        int cz = QuartPos.toSection(z);
        long key = ChunkPos.asLong(cx, cz);
        for (int i = 0; i < this.chunkCount; i++) {
            if (this.chunkKeys[i] == key) return this.chunkRefs[i].getNoiseBiome(x, y, z);
        }
        ChunkAccess chunk = lvl.getChunk(cx, cz, ChunkStatus.BIOMES, false);
        if (chunk == null) return lvl.getUncachedNoiseBiome(x, y, z);
        // Only memoise full chunks (their biome containers are final for the chunk's lifetime); a proto chunk at the
        // generation border could be promoted by main-thread tasks run while a spawn callback blocks on a load.
        if (!(chunk instanceof LevelChunk) && !(chunk instanceof ImposterProtoChunk)) return chunk.getNoiseBiome(x, y, z);
        int slot;
        if (this.chunkCount < CHUNK_SLOTS) {
            slot = this.chunkCount++;
        } else {
            slot = this.chunkNext;
            this.chunkNext = (slot + 1) % CHUNK_SLOTS;
        }
        this.chunkKeys[slot] = key;
        this.chunkRefs[slot] = chunk;
        return chunk.getNoiseBiome(x, y, z);
    }

    // ----------------------------------------------------------------- mobsAt

    /** Called with the result of the mobsAt call in getRandomSpawnMobAt. */
    public static void rememberMobs(ServerLevel level, MobCategory category, BlockPos pos,
                                    WeightedRandomList<MobSpawnSettings.SpawnerData> list) {
        SpawnerLookupCache c = active(level);
        if (c == null) return;
        c.mobsValid = true;
        c.mobsCategory = category;
        c.mobsPos = pos.asLong();
        c.mobsList = list;
    }

    /**
     * Called for the mobsAt call in canSpawnMobAt: returns the remembered list if it was computed for the same
     * level/category/position, else null. Always consumes (clears) the memo.
     */
    public static WeightedRandomList<MobSpawnSettings.SpawnerData> takeMobs(ServerLevel level, MobCategory category, BlockPos pos) {
        SpawnerLookupCache c = active(level);
        if (c == null || !c.mobsValid) return null;
        WeightedRandomList<MobSpawnSettings.SpawnerData> list =
                c.mobsCategory == category && c.mobsPos == pos.asLong() ? c.mobsList : null;
        c.dropMobs();
        return list;
    }
}
