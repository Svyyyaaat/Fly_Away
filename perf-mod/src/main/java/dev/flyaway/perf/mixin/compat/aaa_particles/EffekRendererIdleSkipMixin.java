package dev.flyaway.perf.mixin.compat.aaa_particles;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.flyaway.perf.compat.AaaParticlesState;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mod.chloeprime.aaaparticles.api.client.EffectDefinition;
import mod.chloeprime.aaaparticles.api.client.EffectHolder;
import mod.chloeprime.aaaparticles.api.client.EffectRegistry;
import mod.chloeprime.aaaparticles.api.client.effekseer.EffekseerManager;
import mod.chloeprime.aaaparticles.api.client.effekseer.ParticleEmitter;
import mod.chloeprime.aaaparticles.client.render.EffekRenderer;
import net.minecraft.client.Camera;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * aaa_particles draws its Effekseer world layer at the RETURN of every LevelRenderer.renderLevel (main pass and every
 * Immersive Portals pass) and the first-person layers once per frame - even when not a single effect exists. Each such
 * call does: a depth blit main->particles target, a full colour+depth blit main->DISTORTION_BACKGROUND
 * (RenderUtil.prepareBackgroundBuffer), ~10 glGetInteger round trips, sampler save/restore, stream pipelines over every
 * registered effect and the native BeginUpdate/Update/Draw/EndUpdate calls.
 * Profile: EffekRenderer.draw 1.17% of the render thread (blits alone 0.53%).
 *
 * <p>Fix: skip the whole draw when it provably renders nothing: no EffectDefinition holds any emitter (of any type,
 * so the periodic emitter clean-up of the WORLD draw is not starved), the native manager reports zero live instances,
 * and no effect was started on that manager recently. Every GL state the skipped code touches is restored by it, so
 * skipping is invisible. As soon as an effect is played the next draw runs normally.
 */
@Mixin(value = EffekRenderer.class, remap = false)
public abstract class EffekRendererIdleSkipMixin {
    @Inject(method = "draw(Lmod/chloeprime/aaaparticles/api/client/effekseer/ParticleEmitter$Type;FLcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lnet/minecraft/client/Camera;)V",
            at = @At("HEAD"), cancellable = true)
    private static void flyaway$skipWhenIdle(ParticleEmitter.Type type, float partialTick, PoseStack pose, Matrix4f projection,
                                             Camera camera, CallbackInfo ci) {
        try {
            if (flyaway$isIdle(type)) ci.cancel();
        } catch (RuntimeException e) {
            // Never break rendering because of this optimisation: fall through to the original draw.
        }
    }

    @Unique
    private static boolean flyaway$isIdle(ParticleEmitter.Type type) {
        if (EffectDefinitionAccessor.flyaway$shutdown().get()) return false; // original handles shutdown itself
        EnumMap<ParticleEmitter.Type, EffekseerManager> managers = EffectDefinitionAccessor.flyaway$managers().get();
        EffekseerManager manager = managers.get(type);
        if (manager == null) return false;
        if (AaaParticlesState.playedRecently(manager)) return false;
        if (manager.getImpl().GetTotalInstanceCount() != 0) return false;
        for (Map.Entry<ResourceLocation, EffectHolder> entry : EffectRegistry.entries()) {
            Optional<EffectDefinition> def = entry.getValue().lazyGet();
            if (def.isEmpty()) continue;
            EffectDefinitionAccessor acc = (EffectDefinitionAccessor) def.get();
            for (Set<ParticleEmitter> set : acc.flyaway$oneShotEmitters().values()) {
                if (!set.isEmpty()) return false;
            }
            for (Map<ResourceLocation, ParticleEmitter> map : acc.flyaway$namedEmitters().values()) {
                if (!map.isEmpty()) return false;
            }
        }
        return true;
    }
}
