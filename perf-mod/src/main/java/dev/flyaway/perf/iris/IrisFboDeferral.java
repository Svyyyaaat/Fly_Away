package dev.flyaway.perf.iris;

import dev.flyaway.perf.FlyAwayPerf;
import dev.flyaway.perf.PerfConfig;
import org.lwjgl.opengl.GL30C;

/**
 * Lazy "rebind main target" for Iris entity batches. Render thread only. Must not reference Iris classes
 * (the GlStateManager hooks that use it live in the vanilla mixin area and load even without Iris).
 *
 * Inside a scope (Iris FullyBufferedMultiBufferSource.endBatch / endBatchWithType) every draw does:
 *   ExtendedShader.apply  -> glBindFramebuffer(GL_FRAMEBUFFER, irisFbo)
 *   draw
 *   ExtendedShader.clear  -> main RenderTarget.bindWrite(false) -> glBindFramebuffer(GL_FRAMEBUFFER, mainFbo)
 * and the next draw immediately binds an Iris FBO again (very often the same one). The bind issued from
 * ExtendedShader.clear is recorded as "pending" instead of executed (all Java side effects of bindWrite still
 * run, only the GL call is held back) and is executed lazily:
 *   - dropped if the next GL_FRAMEBUFFER bind comes first (it overrides it anyway); that bind itself is skipped
 *     when it targets the FBO that is still physically bound,
 *   - executed before any GlStateManager operation that reads or writes the bound framebuffer
 *     (draws, clears, blits, read pixels, copy tex, attachment changes, status/gets, READ/DRAW-only binds),
 *   - executed when the scope ends.
 * So the GL framebuffer binding observed by every GlStateManager-based operation is identical to vanilla Iris.
 * The only assumption is that nothing inside the scope binds/uses framebuffers with raw GL calls that bypass
 * GlStateManager; the scope only contains RenderType setup/draw/clear for buffered entity/block-entity geometry.
 */
public final class IrisFboDeferral {
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** Nesting depth of Iris batch scopes; 0 = completely inactive (pass-through). */
    public static int depth;
    /** True while ExtendedShader.clear() runs its main-target bindWrite inside a scope. */
    public static boolean deferNextBind;
    /** FBO that logically should be bound to GL_FRAMEBUFFER but is not yet, or -1. */
    private static int pending = -1;
    /** FBO physically bound to GL_FRAMEBUFFER by the last executed GlStateManager bind in this scope, or -1. */
    private static int physical = -1;
    /** Program physically in use by the last executed GlStateManager._glUseProgram in this scope, or -1. */
    public static int physicalProgram = -1;

    // ---- optional statistics (config key iris.debugStats=true), logged every 10 s ----
    public static final boolean STATS = PerfConfig.getBool("iris.debugStats", false);
    public static long statDeferred, statFlushed, statSkippedRebind, statSkippedProgram, statSkippedUniform, statPushedUniform;
    private static long statNextLog;

    private IrisFboDeferral() {}

    public static void enter() {
        if (depth++ == 0) {
            pending = -1;
            physical = -1;
            physicalProgram = -1;
            deferNextBind = false;
        }
    }

    public static void exit() {
        flush();
        if (--depth <= 0) {
            depth = 0;
            physical = -1;
            physicalProgram = -1;
            deferNextBind = false;
            if (STATS) logStats();
        }
    }

    private static void logStats() {
        long now = System.nanoTime();
        if (now < statNextLog) return;
        statNextLog = now + 10_000_000_000L;
        FlyAwayPerf.LOGGER.info("[iris] last 10s: main rebinds deferred={} executed={} gbuffer rebinds skipped={}, "
                        + "glUseProgram skipped={}, custom uniform pushes skipped={} done={}",
                statDeferred, statFlushed, statSkippedRebind, statSkippedProgram, statSkippedUniform, statPushedUniform);
        statDeferred = statFlushed = statSkippedRebind = statSkippedProgram = statSkippedUniform = statPushedUniform = 0;
    }

    /**
     * Called at HEAD of GlStateManager._glBindFramebuffer. Returns true when the GL call must be skipped.
     */
    public static boolean onBind(int target, int fbo) {
        if (depth == 0) return false;
        if (target == GL_FRAMEBUFFER) {
            if (deferNextBind) {
                pending = fbo; // logically bound now, physically still `physical`
                if (STATS) statDeferred++;
                return true;
            }
            boolean hadPending = pending != -1;
            pending = -1; // superseded by this bind (it sets both read and draw bindings)
            if (hadPending && fbo == physical) {
                if (STATS) statSkippedRebind++;
                return true; // the FBO bound before the deferred rebind is still bound
            }
            physical = fbo;
            return false;
        }
        // GL_READ_FRAMEBUFFER / GL_DRAW_FRAMEBUFFER only: materialise first, then the binding is split -> unknown.
        flush();
        physical = -1;
        return false;
    }

    /** Executes a deferred bind, if any. Cheap no-op otherwise. */
    public static void flush() {
        int f = pending;
        if (f != -1) {
            pending = -1;
            physical = f;
            if (STATS) statFlushed++;
            GL30C.glBindFramebuffer(GL_FRAMEBUFFER, f);
        }
    }

    /** Called before glDeleteFramebuffers: deleting the bound FBO rebinds 0 in GL, so forget what we know. */
    public static void onDelete(int fbo) {
        if (depth == 0) return;
        flush();
        if (fbo == physical) physical = -1;
    }
}
