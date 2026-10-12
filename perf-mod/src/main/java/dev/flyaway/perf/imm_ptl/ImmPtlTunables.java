package dev.flyaway.perf.imm_ptl;

import dev.flyaway.perf.PerfConfig;

/** Tunables for the imm_ptl area (config/flyaway_perf.properties). Defaults are the visually safe values. */
public final class ImmPtlTunables {
    private ImmPtlTunables() {}

    /**
     * {@code imm_ptl.occlusion.noSyncWhenHidden} (default TRUE: the user's Oct 3 profile spent 15% of the render thread stalled in this read). When true, a portal whose previous-frame occlusion
     * query said "hidden" stays hidden this frame instead of Immersive Portals stalling the CPU on this frame's
     * query (glGetQueryObjectiv). Trade-off: a portal that just came out from behind an occluder appears one frame
     * later. See docs/portals.md.
     */
    public static final boolean OCCLUSION_NO_SYNC_WHEN_HIDDEN = PerfConfig.getBool("imm_ptl.occlusion.noSyncWhenHidden", true);

    // ---- portal postcards (docs/portal_postcards.md) ----

    /** {@code imm_ptl.postcards} (default true): show a reprojected snapshot in portals seen through another portal under Iris. */
    public static final boolean POSTCARDS = PerfConfig.getBool("imm_ptl.postcards", true);
    /** {@code imm_ptl.postcards.resolution} (default 768): long side of a postcard texture in pixels (128..2048). */
    public static final int POSTCARD_RESOLUTION = Math.max(128, Math.min(2048, PerfConfig.getInt("imm_ptl.postcards.resolution", 768)));
    /** {@code imm_ptl.postcards.refreshFrames} (default 4): a directly visible portal re-captures its postcard at most every N frames. */
    public static final int POSTCARD_REFRESH_FRAMES = Math.max(1, PerfConfig.getInt("imm_ptl.postcards.refreshFrames", 4));
    /** {@code imm_ptl.postcards.maxMemoryMB} (default 64): GPU memory cap for all postcards together (LRU eviction). */
    public static final int POSTCARD_MAX_MEMORY_MB = Math.max(4, PerfConfig.getInt("imm_ptl.postcards.maxMemoryMB", 64));
    /** {@code imm_ptl.postcards.fadeMs} (default 350): cross-fade time postcard -> live view and swirl -> postcard. */
    public static final int POSTCARD_FADE_MS = Math.max(0, PerfConfig.getInt("imm_ptl.postcards.fadeMs", 350));
    /** {@code imm_ptl.postcards.debug} (default false): tint postcards magenta and log captures/draws. */
    public static final boolean POSTCARD_DEBUG = PerfConfig.getBool("imm_ptl.postcards.debug", false);
}
