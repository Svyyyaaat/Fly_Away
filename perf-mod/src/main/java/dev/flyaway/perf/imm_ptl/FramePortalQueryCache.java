package dev.flyaway.perf.imm_ptl;

import java.util.HashMap;
import java.util.List;

/**
 * Render-thread-only, per-frame memo of "portals in (level, box)" answers. Cleared whenever
 * {@link PortalFrameScope} opens or closes a frame, so it never holds a Level across frames (no leak after leaving
 * a world) and never serves an answer outside the frozen-world window.
 */
public final class FramePortalQueryCache {
    private static final int MAX_ENTRIES = 512;
    private static final HashMap<LevelBoxKey, List<?>> CACHE = new HashMap<>();

    private FramePortalQueryCache() {}

    @SuppressWarnings("unchecked")
    public static <T> List<T> get(LevelBoxKey key) {
        return (List<T>) CACHE.get(key);
    }

    public static void put(LevelBoxKey key, List<?> value) {
        if (CACHE.size() >= MAX_ENTRIES) {
            CACHE.clear();
        }
        CACHE.put(key, value);
    }

    public static void clear() {
        if (!CACHE.isEmpty()) {
            CACHE.clear();
        }
    }
}
