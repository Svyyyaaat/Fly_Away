package dev.flyaway.perf.compat;

/**
 * Render-thread-only bookkeeping for the aaa_particles (Effekseer) patches.
 * See docs/flywheel_particles.md, "aaa_particles".
 */
public final class AaaParticlesState {
    /** Incremented once per client frame (from aaa_particles' own DeltaTracker.run, called in Minecraft.runTick). */
    public static int frame;
    /** Last frame in which the Effekseer manager of each emitter type advanced its simulation. Indexed by Type ordinal. */
    public static final int[] LAST_UPDATE_FRAME = {-1, -1, -1, -1, -1, -1, -1, -1};

    private AaaParticlesState() {}

    public static void onNewFrame() {
        frame++;
    }

    /**
     * Returns true when the manager of this type already advanced its simulation in the current frame (i.e. this is
     * an extra Immersive Portals pass) and marks the type as updated otherwise.
     */
    public static boolean alreadyUpdatedThisFrame(int typeOrdinal) {
        // frame == 0: the frame-counter mixin is disabled/not running -> never suppress an update.
        if (frame == 0 || typeOrdinal < 0 || typeOrdinal >= LAST_UPDATE_FRAME.length) return false;
        if (LAST_UPDATE_FRAME[typeOrdinal] == frame) return true;
        LAST_UPDATE_FRAME[typeOrdinal] = frame;
        return false;
    }

    /** A manager on which an effect was Play()ed within this many frames is never treated as idle. */
    public static final int RECENT_PLAY_FRAMES = 120;

    /** Duck interface implemented on EffekseerManager: frame number of the last Play() (Integer.MIN_VALUE = never). */
    public interface PlayTracker {
        int flyaway$lastPlayFrame();
    }

    public static boolean playedRecently(Object manager) {
        if (!(manager instanceof PlayTracker t)) return true; // tracking mixin disabled: never assume idle
        int last = t.flyaway$lastPlayFrame();
        return last != Integer.MIN_VALUE && frame - last < RECENT_PLAY_FRAMES;
    }
}
