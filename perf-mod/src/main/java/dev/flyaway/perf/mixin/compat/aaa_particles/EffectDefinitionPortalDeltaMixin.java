package dev.flyaway.perf.mixin.compat.aaa_particles;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.flyaway.perf.compat.AaaParticlesState;
import mod.chloeprime.aaaparticles.api.client.EffectDefinition;
import mod.chloeprime.aaaparticles.api.client.effekseer.ParticleEmitter;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;

/**
 * EffectDefinition.draw advances the Effekseer simulation by the frame's delta (manager.update(deltaFrames) plus every
 * emitter's internalUpdateProgress(deltaFrames)) and then draws. With Iris on NeoForge the WORLD layer is drawn at the
 * end of every LevelRenderer.renderLevel, i.e. once per Immersive Portals pass as well, so in frames that render
 * portals the effects were simulated N times with the same delta (they ran N times too fast) and the native Update
 * (EffekseerManagerCore_Update, 0.23% self) ran N times. Profile: 0.46% of the render thread in the portal pass.
 *
 * <p>Fix: the first draw of a manager in a frame keeps the delta, every further draw of that manager in the same frame
 * uses delta 0 - it still calls Update (so per-pass culling/layer state is refreshed) and still draws with the pass'
 * camera, but does not advance time again. Drawing is unchanged; the over-speed bug in portal frames is removed.
 */
@Mixin(value = EffectDefinition.class, remap = false)
public abstract class EffectDefinitionPortalDeltaMixin {
    @WrapMethod(method = "draw(Lmod/chloeprime/aaaparticles/api/client/effekseer/ParticleEmitter$Type;Lorg/joml/Vector3f;Lorg/joml/Vector3f;II[F[FFFLcom/mojang/blaze3d/pipeline/RenderTarget;)V")
    private static void flyaway$onlyFirstPassAdvances(ParticleEmitter.Type type, Vector3f front, Vector3f pos, int w, int h,
                                                      float[] camera, float[] projection, float deltaFrames, float partialTicks,
                                                      RenderTarget background, Operation<Void> original) {
        float delta = AaaParticlesState.alreadyUpdatedThisFrame(type.ordinal()) ? 0.0F : deltaFrames;
        original.call(type, front, pos, w, h, camera, projection, delta, partialTicks, background);
    }
}
