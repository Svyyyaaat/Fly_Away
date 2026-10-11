package dev.flyaway.perf.mixin.iris;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.shaders.Uniform;
import dev.flyaway.perf.iris.DedupUniform;
import dev.flyaway.perf.iris.IrisFboDeferral;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Iris ExtendedShader: skip re-uploading vanilla uniforms (iris_ModelViewMat, iris_ProjMat, iris_NormalMat,
 * iris_ColorModulator, iris_Fog*, iris_TextureMat, iris_ChunkOffset, ...) whose value the program already holds.
 *
 * Problem: ExtendedShader.apply() -> updateUniforms() uploads every vanilla Uniform with glUniform* on every draw
 * (vanilla Uniform.upload() ignores its dirty flag, and setDefaultUniforms() re-sets every value anyway), and the
 * three matrices ModelViewMatInverse/ProjMatInverse/NormalMat are even uploaded twice per draw (once explicitly,
 * once from the uniform list). In an entity batch almost all of these values are identical from draw to draw.
 * Profile: 0.48% of the render thread in Uniform.upload under ExtendedShader.updateUniforms.
 *
 * Fix: at the upload call inside uploadIfNotNull (only reached from updateUniforms, right after apply() made this
 * program current) we compare the uniform's value with the value we last uploaded to this program and skip the
 * glUniform call when it is bit-identical. GL program uniforms persist per program, so the program still holds
 * exactly that value - provided nobody else wrote that location in between:
 *  - every other Uniform.upload() (Sable, Epic Fight, Veil, ...) drops the snapshot (vanilla.iris.UniformUploadDedupMixin);
 *  - program relink / swap (Veil shader_recompile) goes through Uniform.setLocation() which drops the snapshot;
 *  - locations Iris itself writes for this program (built-in uniforms - e.g. iris_FogColor/FogStart/FogEnd are
 *    ALSO Iris PER_FRAME uniforms -, shader-pack custom uniforms, API uniforms) are excluded: we record every
 *    location the program's ProgramUniforms.Builder handed out and never dedup a vanilla uniform on one of them;
 *  - only uniforms created by this very shader are deduped (Uniform.parent == this; Iris' shared FAKE_UNIFORM is not).
 * Values legitimately changing between draws or between Immersive Portals / shadow passes are simply uploaded.
 *
 * Expected: ~0.3-0.4% of the render thread. Risk: low (assumes no mod writes iris_* vanilla uniform locations of
 * Iris programs with raw glUniform*; none in the pack does - grepped all mod sources for those names).
 * Disable: mixin.iris.ExtendedShaderUniformDedupMixin=false.
 */
@Mixin(value = ExtendedShader.class, remap = false)
public abstract class ExtendedShaderUniformDedupMixin {
    /** Locations owned by Iris' ProgramUniforms/CustomUniforms for this program; null = unknown (no dedup). */
    @Unique
    private IntOpenHashSet flyaway$irisLocations;

    @WrapOperation(
            method = "<init>",
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/gl/program/ProgramUniforms$Builder;buildUniforms()Lnet/irisshaders/iris/gl/program/ProgramUniforms;"))
    private ProgramUniforms flyaway$captureIrisLocations(ProgramUniforms.Builder builder, Operation<ProgramUniforms> original) {
        ProgramUniforms result = original.call(builder);
        // customUniforms.mapholderToPass runs before buildUniforms, so the map is complete here
        if ((Object) builder instanceof ProgramUniformsBuilderAccessor accessor) {
            this.flyaway$irisLocations = new IntOpenHashSet(accessor.flyaway$getLocations().keySet());
        }
        return result;
    }

    @WrapOperation(
            method = "uploadIfNotNull(Lcom/mojang/blaze3d/shaders/Uniform;)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/shaders/Uniform;upload()V"))
    private void flyaway$uploadIfChanged(Uniform uniform, Operation<Void> original) {
        IntOpenHashSet irisLocations = this.flyaway$irisLocations;
        if (irisLocations != null && (Object) uniform instanceof DedupUniform dedup && dedup.flyaway$owner() == (Object) this) {
            int location = uniform.getLocation();
            if (location >= 0 && !irisLocations.contains(location)) {
                boolean uploaded = dedup.flyaway$uploadIfChanged();
                if (IrisFboDeferral.STATS) {
                    if (uploaded) IrisFboDeferral.statUploadedVanillaUniform++;
                    else IrisFboDeferral.statSkippedVanillaUniform++;
                }
                return;
            }
        }
        original.call(uniform);
    }
}
