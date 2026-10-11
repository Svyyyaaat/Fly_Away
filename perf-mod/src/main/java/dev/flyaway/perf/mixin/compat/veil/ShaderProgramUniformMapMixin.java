package dev.flyaway.perf.mixin.compat.veil;

import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import foundry.veil.impl.client.render.shader.uniform.ShaderUniformImpl;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What: Veil's {@code ShaderProgramImpl} keeps its name -> uniform table in an {@code Object2ObjectArrayMap}, i.e. a
 * linear scan with {@code String.equals} over all active uniforms of the program.
 *
 * <p>Why: when a Veil program is used as a vanilla {@code ShaderInstance} (Veil's {@code Wrapper}, used by
 * Sable/Aeronautics and Create Simulated shaders on every Sodium chunk layer and sub-level draw), every vanilla
 * {@code Uniform.set(...)} goes through {@code UniformWrapper -> program.getUniform(name)}, so each
 * {@code setDefaultUniforms} does a dozen full scans per draw. Profile: {@code ShaderProgramImpl.getUniform} 0.19%
 * (2026-10-11) / 0.27% (2026-10-03) of the render thread, nearly all of it {@code Objects.equals} inside
 * {@code Object2ObjectArrayMap.get}.
 *
 * <p>Fix: swap the (still empty) map for an {@code Object2ObjectOpenHashMap} at the end of the constructor. The field
 * is private. It is only used through get/put/clear/values() and the copy constructor in {@code applyProgram}, and
 * nothing depends on its iteration order (it is only iterated to free uniforms). String hashes are cached, so a lookup
 * becomes one probe and one equals. The same uniform objects are returned. Identical behaviour.
 * Disable: {@code mixin.compat.ShaderProgramUniformMapMixin=false}.
 */
@Mixin(value = ShaderProgramImpl.class, remap = false)
public abstract class ShaderProgramUniformMapMixin {
    @Shadow
    @Final
    @Mutable
    private Object2ObjectMap<String, ShaderUniformImpl> uniforms;

    @Inject(method = "<init>(Lnet/minecraft/resources/ResourceLocation;)V", at = @At("RETURN"))
    private void flyaway$hashUniformTable(ResourceLocation name, CallbackInfo ci) {
        this.uniforms = new Object2ObjectOpenHashMap<>(this.uniforms);
    }
}
