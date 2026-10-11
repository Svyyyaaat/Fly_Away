package dev.flyaway.perf.vanilla.entity;

/**
 * A monotonically increasing "client frame" number plus the identity of the client (render) thread.
 * <p>
 * {@link #frame} is bumped at the very start of every {@code Minecraft.runTick} (see
 * {@code mixin.vanilla.entity.MinecraftFrameStampMixin}), i.e. before queued network packets are processed, before
 * the client tick and before the frame is rendered. Anything that only changes through packets, the client tick or
 * input handling (which happens at the end of runTick, in pollEvents) is therefore constant for the rest of a frame,
 * so per-frame caches keyed on this number are exact.
 * <p>
 * Plain class without any game references so it is safe to load from common mixins on a dedicated server
 * ({@link #renderThread} just stays null there and every cache falls through to the original code).
 */
public final class FrameStamp {
    /** Only written and read on the client thread. */
    public static long frame;
    /** The client thread once the first frame started; null on dedicated servers / when the stamp mixin is off. */
    public static volatile Thread renderThread;

    private FrameStamp() {}

    public static void onRunTick() {
        frame++;
        if (renderThread == null) renderThread = Thread.currentThread();
    }

    /** True when called on the client thread and the frame stamp is running. */
    public static boolean onClientThread() {
        return Thread.currentThread() == renderThread;
    }
}
