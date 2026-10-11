package dev.flyaway.perf.mixin.iris;

import dev.flyaway.perf.iris.VersionedCachedUniform;
import net.irisshaders.iris.uniforms.custom.cached.CachedUniform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Gives every Iris custom/input CachedUniform a version that changes whenever its cached value can change
 * (update() is the only place that calls doUpdate()). Used by {@link CustomUniformsPushDedupMixin}.
 * No behaviour change on its own.
 */
@Mixin(value = CachedUniform.class, remap = false)
public abstract class CachedUniformVersionMixin implements VersionedCachedUniform {
    @Unique
    private long flyaway$version;

    @Inject(method = "update()V", at = @At("HEAD"))
    private void flyaway$bumpVersion(CallbackInfo ci) {
        this.flyaway$version = VersionedCachedUniform.Counter.next();
    }

    @Override
    public long flyaway$version() {
        return this.flyaway$version;
    }
}
