package dev.flyaway.perf.iris;

/**
 * Implemented on vanilla {@code com.mojang.blaze3d.shaders.Uniform} by
 * {@code dev.flyaway.perf.mixin.vanilla.iris.UniformUploadDedupMixin}. Used by
 * {@code dev.flyaway.perf.mixin.iris.ExtendedShaderUniformDedupMixin}. Pure vanilla types only (no Iris imports),
 * because the vanilla mixin also applies without Iris.
 */
public interface DedupUniform {
    /**
     * Uploads the uniform unless its current value is bit-identical to the value this method last uploaded
     * AND nothing has invalidated that snapshot since (any other {@code upload()} call or {@code setLocation()}).
     * The caller guarantees that the uniform's owner program is the currently bound program.
     *
     * @return true if glUniform* was called
     */
    boolean flyaway$uploadIfChanged();

    /** The shader that created this uniform ({@code Uniform.parent}). */
    Object flyaway$owner();
}
