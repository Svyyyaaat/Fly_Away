package dev.flyaway.perf.iris;

/**
 * Implemented (via mixin) by Iris' {@code CachedUniform}: a value that changes every time Iris re-evaluates the
 * uniform ({@code CachedUniform.update()}, once per world-render pass). The cached value Iris pushes to GL can only
 * change in update(), so "version unchanged" means "the value we pushed last time is still the current value".
 */
public interface VersionedCachedUniform {
    long flyaway$version();

    /** Global counter so versions are unique across all uniforms; starts at 1 (0 = never updated). */
    final class Counter {
        private static long next = 1;

        private Counter() {}

        /** Render thread only (CachedUniform.update runs from IrisRenderingPipeline.beginLevelRendering). */
        public static long next() {
            return next++;
        }
    }
}
