package dev.flyaway.perf.epicfight;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Per-render-pass memo of the world reads that smooth lighting (vanilla {@code AmbientOcclusionFace.calculate}) does
 * while Epic Fight's {@code FractureBlockRenderer} re-tessellates every fracture block every frame.
 * <p>
 * Each fracture block is drawn with {@code BlockRenderDispatcher.renderBreakingTexture} at {@code pos.above()}: per
 * face up to 9 {@code LevelRenderer.getLightColor} (light engine x2 + LambDynLights + micimmptl wrappers), up to 9
 * shade-brightness and ~15 {@code getBlockState} lookups - ~50 light lookups per block per frame, nearly all on
 * positions shared with the neighbouring fracture blocks (a ground slam turns a whole disc of blocks into fracture
 * blocks). The render thread never caches these (vanilla's {@code ModelBlockRenderer.Cache} is only enabled by chunk
 * meshing). Profile 76w5vCPcjb: 5.0% of the render thread in these lookups, 7.13% for the whole renderer.
 * <p>
 * <b>Why the memo is exact.</b> Inside one {@code LevelRenderer.renderLevel} call nothing changes the client world:
 * packets, client ticks (block entity ticks, LambDynLights' per-tick spatial lookup), block changes all run between
 * frames, and {@code pollLightUpdates}/{@code runLightUpdates} run at the top of renderLevel, before any block entity
 * is drawn. The memo epoch is bumped at renderLevel HEAD and RETURN, so a nested Immersive Portals pass (which runs
 * light updates again) starts with a fresh memo, and the outer pass continues with a fresh one after it. Entries are
 * keyed by the getter object (memo dropped when another one is passed), the position, and - for light and shade -
 * the identity of the BlockState argument, because vanilla passes the <i>rendered</i> state for the centre sample
 * (emission / emissive rendering depend on it). So every memoised answer is the value the original call returned
 * earlier in the same pass for the same arguments.
 * <p>
 * Only used on the thread that opened the pass, and only while {@link #inFracture} is set by
 * {@code mixin.epicfight.FractureBlockRendererLightMemoMixin} around Epic Fight's own renderBreakingTexture call; no
 * other model rendering (Create contraptions, Ponder, Sable sub-levels, pistons, chunk meshing threads) sees it.
 */
public final class FractureLightMemo {
    private FractureLightMemo() {}

    /** Set (render thread only) while FractureBlockRenderer's renderBreakingTexture call runs. */
    public static boolean inFracture;

    private static Thread passThread;
    private static int depth;
    private static int epoch;

    private static int memoEpoch = -1;
    private static BlockAndTintGetter memoLevel;
    private static final Long2ObjectOpenHashMap<Entry> MEMO = new Long2ObjectOpenHashMap<>();
    /** A radius-20 slam touches ~1250 blocks x 3 layers; beyond this simply start over (still exact). */
    private static final int MAX_ENTRIES = 1 << 15;

    /** Memo slot for one position. A null state field means "not memoised yet". */
    public static final class Entry {
        public BlockState blockState;
        public BlockState lightArg;
        public int light;
        public BlockState shadeArg;
        public float shade;
    }

    // ---- pass scope (mixin.vanilla.epicfight.LevelRendererFracturePassMixin / GameRendererFractureFrameMixin) ----

    public static void enterPass() {
        depth++;
        epoch++;
        passThread = Thread.currentThread();
    }

    public static void exitPass() {
        if (depth > 0 && --depth == 0) passThread = null;
        epoch++;
    }

    /** GameRenderer.render HEAD: force-close the scope once per frame (in case some mod cancelled renderLevel). */
    public static void newFrame() {
        depth = 0;
        passThread = null;
        epoch++;
        memoLevel = null;
        if (MEMO.size() > 4096) {
            MEMO.clear();
            MEMO.trim(1024);
        } else {
            MEMO.clear();
        }
    }

    /**
     * The memo slot for {@code pos} in {@code level}, or null when the memo must not be used for this call (not inside
     * a fracture render on the pass thread). Callers fill the slot with the original result on a miss.
     */
    public static Entry slot(BlockAndTintGetter level, long pos) {
        if (!inFracture || depth <= 0 || passThread != Thread.currentThread()) return null;
        if (memoEpoch != epoch || memoLevel != level || MEMO.size() >= MAX_ENTRIES) {
            MEMO.clear();
            memoEpoch = epoch;
            memoLevel = level;
        }
        Entry e = MEMO.get(pos);
        if (e == null) {
            e = new Entry();
            MEMO.put(pos, e);
        }
        return e;
    }
}
