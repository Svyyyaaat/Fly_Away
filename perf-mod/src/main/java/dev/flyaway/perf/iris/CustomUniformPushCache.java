package dev.flyaway.perf.iris;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;

/** Per-program snapshot of Iris' custom-uniform location map plus the uniform version last uploaded. */
public final class CustomUniformPushCache {
    public final Object2IntMap<CachedUniform> source;
    public final CachedUniform[] uniforms;
    public final int[] locations;
    public final long[] pushedVersion;

    public CustomUniformPushCache(Object2IntMap<CachedUniform> source) {
        this.source = source;
        int n = source.size();
        this.uniforms = new CachedUniform[n];
        this.locations = new int[n];
        this.pushedVersion = new long[n];
        int i = 0;
        for (Object2IntMap.Entry<CachedUniform> e : Object2IntMaps.fastIterable(source)) {
            this.uniforms[i] = e.getKey();
            this.locations[i] = e.getIntValue();
            this.pushedVersion[i] = -1L; // never pushed to this program yet
            i++;
        }
    }
}
