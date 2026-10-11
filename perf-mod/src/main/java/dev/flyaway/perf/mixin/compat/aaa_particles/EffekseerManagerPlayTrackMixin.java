package dev.flyaway.perf.mixin.compat.aaa_particles;

import dev.flyaway.perf.compat.AaaParticlesState;
import mod.chloeprime.aaaparticles.api.client.effekseer.EffekseerEffect;
import mod.chloeprime.aaaparticles.api.client.effekseer.EffekseerManager;
import mod.chloeprime.aaaparticles.api.client.effekseer.ParticleEmitter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembers the frame in which an effect was last started on this manager. Every Play() of the mod goes through
 * createParticle(effect, type) (the 1-arg overload delegates to it). {@link EffekRendererIdleSkipMixin} never skips a
 * manager that started an effect within the last {@link AaaParticlesState#RECENT_PLAY_FRAMES} frames, so a freshly started effect always gets its first Update() even when the native
 * instance counter has not caught up yet and the emitter is not tracked by an EffectDefinition (direct API use).
 */
@Mixin(value = EffekseerManager.class, remap = false)
public abstract class EffekseerManagerPlayTrackMixin implements AaaParticlesState.PlayTracker {
    @Unique
    private int flyaway$lastPlay = Integer.MIN_VALUE;

    @Inject(method = "createParticle(Lmod/chloeprime/aaaparticles/api/client/effekseer/EffekseerEffect;Lmod/chloeprime/aaaparticles/api/client/effekseer/ParticleEmitter$Type;)Lmod/chloeprime/aaaparticles/api/client/effekseer/ParticleEmitter;",
            at = @At("HEAD"))
    private void flyaway$markPlayed(EffekseerEffect effect, ParticleEmitter.Type type, CallbackInfoReturnable<ParticleEmitter> cir) {
        this.flyaway$lastPlay = AaaParticlesState.frame;
    }

    @Override
    public int flyaway$lastPlayFrame() {
        return this.flyaway$lastPlay;
    }
}
