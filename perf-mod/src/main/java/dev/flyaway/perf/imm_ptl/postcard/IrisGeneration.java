package dev.flyaway.perf.imm_ptl.postcard;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.PipelineManager;

/**
 * Changes whenever Iris destroys its pipelines (shader pack reload, settings change): the identity of the pipeline
 * manager mixed with its reload counter. Postcards are cleared when this changes. Only called from the Iris
 * compatibility portal renderer path, so Iris is present.
 */
final class IrisGeneration {
    private static boolean broken;

    private IrisGeneration() {}

    static int get() {
        if (broken) return 0;
        try {
            PipelineManager m = Iris.getPipelineManager();
            return m == null ? 0 : System.identityHashCode(m) * 31 + m.getVersionCounterForSodiumShaderReload();
        } catch (Throwable t) {
            broken = true;
            return 0;
        }
    }
}
