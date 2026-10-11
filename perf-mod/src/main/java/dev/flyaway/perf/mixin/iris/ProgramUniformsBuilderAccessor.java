package dev.flyaway.perf.mixin.iris;

import java.util.Map;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read access to the uniform locations an Iris ProgramUniforms.Builder handed out (Iris built-in uniforms, shader
 * pack custom uniforms, Voxy/other API uniforms - everything uploaded by Iris itself rather than by the vanilla
 * Uniform objects). Used by {@link ExtendedShaderUniformDedupMixin}. No behaviour change.
 */
@Mixin(value = ProgramUniforms.Builder.class, remap = false)
public interface ProgramUniformsBuilderAccessor {
    @Accessor("locations")
    Map<Integer, String> flyaway$getLocations();
}
