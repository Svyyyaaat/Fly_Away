package dev.flyaway.perf.mixin.compat.aaa_particles;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import mod.chloeprime.aaaparticles.api.client.EffectDefinition;
import mod.chloeprime.aaaparticles.api.client.effekseer.EffekseerManager;
import mod.chloeprime.aaaparticles.api.client.effekseer.ParticleEmitter;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read access to the emitter containers / global managers, used by {@link EffekRendererIdleSkipMixin}. */
@Mixin(value = EffectDefinition.class, remap = false)
public interface EffectDefinitionAccessor {
    @Accessor("oneShotEmitters")
    EnumMap<ParticleEmitter.Type, Set<ParticleEmitter>> flyaway$oneShotEmitters();

    @Accessor("namedEmitters")
    EnumMap<ParticleEmitter.Type, Map<ResourceLocation, ParticleEmitter>> flyaway$namedEmitters();

    @Accessor("THE_ONE_MANAGERS")
    static Supplier<EnumMap<ParticleEmitter.Type, EffekseerManager>> flyaway$managers() {
        throw new AssertionError();
    }

    @Accessor("SHUTDOWN")
    static AtomicBoolean flyaway$shutdown() {
        throw new AssertionError();
    }
}
