package dev.flyaway.perf.mixin.vanilla.iris;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ShaderInstance.setDefaultUniforms(): use constant sampler names instead of building "Sampler" + i 12 times per draw.
 *
 * Problem: setDefaultUniforms runs before EVERY draw through VertexBuffer.drawWithShader / BufferUploader (all Iris
 * entity batches, Sable sub-levels, Epic Fight meshes, ...) and starts with
 * {@code for (i = 0; i < 12; i++) setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));}. Every iteration
 * allocates a fresh String, whose hash is then computed by HashMap.put and compared char-by-char with the key.
 * Profile: setSampler -> HashMap.put 0.47% of the render thread (putVal 0.30, hash 0.12).
 *
 * Fix: the vanilla loop bound 12 is turned into 0 and the identical loop runs at HEAD with interned constant names
 * (hash cached, no allocation). Same calls, same order (nothing else injects at the start of this method in the
 * pack: Veil hooks TAIL, Sodium Extra wraps the fog getters), the virtual setSampler call is kept so Veil's
 * ShaderProgramImpl.Wrapper override still sees every sampler.
 *
 * Expected: ~0.2-0.3% of the render thread. Risk: very low. Disable: mixin.vanilla.ShaderInstanceSamplerNamesMixin=false.
 */
@Mixin(ShaderInstance.class)
public abstract class ShaderInstanceSamplerNamesMixin {
    @Unique
    private static final String[] FLYAWAY$SAMPLER_NAMES = {
            "Sampler0", "Sampler1", "Sampler2", "Sampler3", "Sampler4", "Sampler5",
            "Sampler6", "Sampler7", "Sampler8", "Sampler9", "Sampler10", "Sampler11"
    };

    @Shadow
    public abstract void setSampler(String name, Object value);

    @Inject(method = "setDefaultUniforms", at = @At("HEAD"))
    private void flyaway$setSamplersWithConstantNames(VertexFormat.Mode mode, Matrix4f modelView, Matrix4f projection,
                                                     Window window, CallbackInfo ci) {
        String[] names = FLYAWAY$SAMPLER_NAMES;
        for (int i = 0; i < 12; i++) {
            int texture = RenderSystem.getShaderTexture(i);
            this.setSampler(names[i], texture);
        }
    }

    @ModifyConstant(method = "setDefaultUniforms", constant = @Constant(intValue = 12, ordinal = 0))
    private int flyaway$skipVanillaSamplerLoop(int twelve) {
        return 0;
    }
}
