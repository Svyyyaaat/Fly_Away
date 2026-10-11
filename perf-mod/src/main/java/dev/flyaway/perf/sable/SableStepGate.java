package dev.flyaway.perf.sable;

import dev.flyaway.perf.FlyAwayPerf;
import dev.flyaway.perf.PerfConfig;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

/**
 * Tracks live Rapier box bodies per physics scene so an empty scene's native step can be skipped.
 * Sable creates one Rapier scene per loaded dimension and steps it every substep even when the dimension
 * contains no dynamic body at all (only static terrain voxel colliders), which still costs broad-phase /
 * query-pipeline work in native code. Only touched from the server thread, but synchronized for safety.
 */
public final class SableStepGate {
    public static final boolean ENABLED = PerfConfig.getBool("sable.skipEmptyPhysicsStep", true);
    private static final boolean STATS = PerfConfig.getBool("sable.debugStats", false);
    private static final Long2IntOpenHashMap BOXES = new Long2IntOpenHashMap();
    private static long skipped, stepped, nextLog;

    private SableStepGate() {}

    public static synchronized void boxAdded(long scene) {
        BOXES.addTo(scene, 1);
    }

    public static synchronized void boxRemoved(long scene) {
        int n = BOXES.addTo(scene, -1) - 1;
        if (n <= 0) BOXES.remove(scene);
    }

    public static synchronized boolean hasBoxes(long scene) {
        return BOXES.get(scene) > 0;
    }

    public static synchronized void sceneDisposed(long scene) {
        BOXES.remove(scene);
    }

    public static void count(boolean skip) {
        if (!STATS) return;
        if (skip) skipped++; else stepped++;
        long now = System.nanoTime();
        if (now > nextLog) {
            nextLog = now + 30_000_000_000L;
            FlyAwayPerf.LOGGER.info("[sable] physics substeps: skipped(empty)={} stepped={}", skipped, stepped);
            skipped = stepped = 0;
        }
    }
}
