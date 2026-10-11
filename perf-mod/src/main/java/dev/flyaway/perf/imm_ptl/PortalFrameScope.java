package dev.flyaway.perf.imm_ptl;

/**
 * Marks the window in which the client is inside {@code GameRenderer.renderLevel} (set by
 * {@code mixin.vanilla.portals.GameRendererFrameScopeMixin}). Immersive Portals renders each portal view by calling
 * {@code renderLevel} again from inside the outer call, so this is a depth counter: the window opens when the
 * outermost call starts and closes when it returns. Inside that window no client tick and no packet handling runs,
 * so the set of loaded entities and their positions are frozen (portal geometry changes are caught separately by
 * {@code PortalGeometryInvalidateMixin}), and per-frame caches ({@link FramePortalQueryCache}) may be used - but only
 * while {@link #isActiveOnThisThread()}.
 * <p>
 * The cache is cleared when the window opens and when it closes, so an answer computed in frame N is never seen by
 * code running between frames (ticks) or in frame N+1. {@link #resetForNewFrame()} (GameRenderer.render HEAD) also
 * force-closes the window, so even an exception escaping a nested pass cannot leave it open for longer than the
 * rest of that frame.
 */
public final class PortalFrameScope {
    private static volatile Thread renderThread;
    private static int depth;

    private PortalFrameScope() {}

    public static void enterRenderLevel() {
        if (depth++ == 0) {
            FramePortalQueryCache.clear();
            renderThread = Thread.currentThread();
        }
    }

    public static void exitRenderLevel() {
        if (depth > 0 && --depth == 0) {
            renderThread = null;
            FramePortalQueryCache.clear();
        }
    }

    public static void resetForNewFrame() {
        depth = 0;
        renderThread = null;
        FramePortalQueryCache.clear();
    }

    public static boolean isActiveOnThisThread() {
        return renderThread == Thread.currentThread();
    }
}
