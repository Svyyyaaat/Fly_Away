package dev.flyaway.perf.imm_ptl;

import dev.flyaway.perf.PerfConfig;

/** Tunables for the imm_ptl area (config/flyaway_perf.properties). Defaults are the visually safe values. */
public final class ImmPtlTunables {
    private ImmPtlTunables() {}

    /**
     * {@code imm_ptl.occlusion.noSyncWhenHidden} (default false). When true, a portal whose previous-frame occlusion
     * query said "hidden" stays hidden this frame instead of Immersive Portals stalling the CPU on this frame's
     * query (glGetQueryObjectiv). Trade-off: a portal that just came out from behind an occluder appears one frame
     * later. See docs/portals.md.
     */
    public static final boolean OCCLUSION_NO_SYNC_WHEN_HIDDEN = PerfConfig.getBool("imm_ptl.occlusion.noSyncWhenHidden", false);
}
