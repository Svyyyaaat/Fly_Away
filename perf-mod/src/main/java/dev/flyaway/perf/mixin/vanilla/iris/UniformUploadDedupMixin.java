package dev.flyaway.perf.mixin.vanilla.iris;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import dev.flyaway.perf.iris.DedupUniform;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Value snapshot for vanilla shader uniforms, so Iris' ExtendedShader can skip re-uploading a uniform whose value
 * its program already holds (see mixin.iris.ExtendedShaderUniformDedupMixin, the only caller of
 * {@link #flyaway$uploadIfChanged()}). On its own this mixin changes nothing: the two HEAD hooks only clear the
 * snapshot flag.
 *
 * Invalidation (snapshot is dropped, so the next ExtendedShader draw uploads unconditionally):
 *  - any regular {@code upload()} call from anywhere (e.g. Sable's sable$refreshModelMatrices, Epic Fight), because
 *    glUniform* writes to whatever program happens to be bound, so we cannot know where that value went;
 *  - {@code setLocation()} - Veil's shader_recompile swaps a ShaderInstance's program and re-resolves every
 *    uniform location through it (veil$invalidate), a relinked program has fresh default values.
 *
 * Disable: mixin.vanilla.UniformUploadDedupMixin=false (ExtendedShader then falls back to plain upload()).
 */
@Mixin(Uniform.class)
public abstract class UniformUploadDedupMixin implements DedupUniform {
    @Shadow @Final private int count;
    @Shadow @Final private int type;
    @Shadow @Final private IntBuffer intValues;
    @Shadow @Final private FloatBuffer floatValues;
    @Shadow private boolean dirty;
    @Shadow @Final private Shader parent;

    @Shadow public abstract void upload();

    /** Raw bits of the last value uploaded by flyaway$uploadIfChanged (float values via floatToRawIntBits). */
    @Unique private int[] flyaway$last;
    @Unique private boolean flyaway$valid;

    @Inject(method = "upload()V", at = @At("HEAD"))
    private void flyaway$invalidateOnUpload(CallbackInfo ci) {
        this.flyaway$valid = false;
    }

    @Inject(method = "setLocation(I)V", at = @At("HEAD"))
    private void flyaway$invalidateOnLocation(int newLocation, CallbackInfo ci) {
        this.flyaway$valid = false;
    }

    @Override
    public boolean flyaway$uploadIfChanged() {
        int t = this.type;
        int n = this.count;
        if (t > 10 || n <= 0) { // invalid type: upload() only logs; keep exact behaviour
            this.upload();
            return true;
        }
        int[] last = this.flyaway$last;
        if (this.flyaway$valid && last != null && last.length == n && this.flyaway$equalsSnapshot(t, n, last)) {
            this.dirty = false; // what upload() would have done on the Java side
            return false;
        }
        this.upload(); // clears flyaway$valid via the HEAD hook
        if (last == null || last.length != n) {
            last = new int[n];
            this.flyaway$last = last;
        }
        if (t <= 3) {
            IntBuffer b = this.intValues;
            for (int i = 0; i < n; i++) last[i] = b.get(i);
        } else {
            FloatBuffer b = this.floatValues;
            for (int i = 0; i < n; i++) last[i] = Float.floatToRawIntBits(b.get(i));
        }
        this.flyaway$valid = true;
        return true;
    }

    @Unique
    private boolean flyaway$equalsSnapshot(int t, int n, int[] last) {
        if (t <= 3) {
            IntBuffer b = this.intValues;
            for (int i = 0; i < n; i++) if (b.get(i) != last[i]) return false;
        } else {
            FloatBuffer b = this.floatValues;
            for (int i = 0; i < n; i++) if (Float.floatToRawIntBits(b.get(i)) != last[i]) return false;
        }
        return true;
    }

    @Override
    public Object flyaway$owner() {
        return this.parent;
    }
}
