package dev.flyaway.perf.vanilla.entity;

/**
 * A monotonically increasing client "epoch" number plus the identity of the client (render) thread, used as an
 * exact key for short-lived caches of "what does this entity have equipped" style queries on the client thread.
 * <p>
 * {@link #epoch} is bumped (see {@code mixin.vanilla.entity.MinecraftFrameStampMixin}) at every point where the
 * client thread can change inventories/equipment:
 * <ul>
 *   <li>start of {@code Minecraft.runTick} - before queued network packets (server syncs) are processed;</li>
 *   <li>start of {@code Minecraft.tick}, right after {@code handleKeybinds()} (item use / hotbar / equip keys) and at
 *       the end of {@code Minecraft.tick}.</li>
 * </ul>
 * Mouse/keyboard GUI input (inventory clicks) is dispatched from {@code pollEvents} at the end of runTick, i.e. after
 * the render and before the next runTick bump. So between two bumps the client-side inventories are constant and a
 * cache keyed on the epoch returns exactly what a fresh query would.
 * <p>
 * Plain class without any game references so it is safe to load from common mixins on a dedicated server
 * ({@link #renderThread} just stays null there and every cache falls through to the original code).
 */
public final class FrameStamp {
    /** Only written and read on the client thread. */
    public static long epoch;
    /** The client thread once the first frame started; null on dedicated servers / when the stamp mixin is off. */
    public static volatile Thread renderThread;

    private FrameStamp() {}

    public static void bump() {
        epoch++;
        if (renderThread == null) renderThread = Thread.currentThread();
    }

    /** True when called on the client thread and the stamp is running. */
    public static boolean onClientThread() {
        return Thread.currentThread() == renderThread;
    }
}
