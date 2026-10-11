package dev.flyaway.perf.mixin.iris;

import dev.flyaway.perf.iris.CustomUniformPushCache;
import dev.flyaway.perf.iris.IrisFboDeferral;
import dev.flyaway.perf.iris.VersionedCachedUniform;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.util.IdentityHashMap;
import java.util.Map;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris: skip re-uploading shader-pack custom uniforms that a program already holds.
 *
 * Problem: CustomUniforms.push(pass) is called on EVERY draw (ExtendedShader.apply, SodiumShader.setupState,
 * composite passes, Colorwheel programs) and uploads every custom/input uniform of that program with
 * glUniform*, because CachedUniform.changed is set by every update() and never cleared (markUnchanged() is dead
 * code in Iris 1.8). Profile: CustomUniforms.push 1.76% of the render thread (1.11% from ExtendedShader alone).
 *
 * Fix: GL program uniforms persist per program. A CachedUniform's value only changes in update() (once per
 * world-render pass, including each Immersive Portals pass), so per (pass object, uniform) we remember the
 * update-version last pushed and skip the glUniform call when it has not changed. Every location in the map is
 * owned exclusively by CustomUniforms (ProgramUniforms.Builder.location() refuses duplicate names/locations),
 * so nothing else overwrites these values in between. All push(pass) callers bind the pass' program first.
 *
 * Expected: ~1.2-1.5% render thread. Risk: low. Disable: mixin.iris.CustomUniformsPushDedupMixin=false.
 */
@Mixin(value = CustomUniforms.class, remap = false)
public abstract class CustomUniformsPushDedupMixin {
    @Shadow
    @Final
    private Map<Object, Object2IntMap<CachedUniform>> locationMap;

    @Unique
    private final IdentityHashMap<Object, CustomUniformPushCache> flyaway$pushCache = new IdentityHashMap<>();

    @Inject(method = "push(Ljava/lang/Object;)V", at = @At("HEAD"), cancellable = true)
    private void flyaway$pushChangedOnly(Object pass, CallbackInfo ci) {
        ci.cancel();
        Object2IntMap<CachedUniform> uniforms = this.locationMap.get(pass);
        if (uniforms == null) return; // same as original: nothing to push
        CustomUniformPushCache cache = this.flyaway$pushCache.get(pass);
        if (cache == null || cache.source != uniforms || cache.uniforms.length != uniforms.size()) {
            cache = new CustomUniformPushCache(uniforms);
            this.flyaway$pushCache.put(pass, cache);
        }
        CachedUniform[] us = cache.uniforms;
        int[] locs = cache.locations;
        long[] pushed = cache.pushedVersion;
        for (int i = 0; i < us.length; i++) {
            CachedUniform u = us[i];
            if (!(u instanceof VersionedCachedUniform versioned)) { // CachedUniformVersionMixin disabled
                u.pushIfChanged(locs[i]);
                continue;
            }
            long v = versioned.flyaway$version();
            if (pushed[i] != v) {
                u.pushIfChanged(locs[i]);
                pushed[i] = v;
                if (IrisFboDeferral.STATS) IrisFboDeferral.statPushedUniform++;
            } else if (IrisFboDeferral.STATS) {
                IrisFboDeferral.statSkippedUniform++;
            }
        }
    }
}
