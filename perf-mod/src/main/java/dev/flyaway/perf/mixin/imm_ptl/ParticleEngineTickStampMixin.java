package dev.flyaway.perf.mixin.imm_ptl;

import dev.flyaway.perf.imm_ptl.ParticleTickStamp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records which level's particles are being ticked (IP ticks remote-world particles under withSwitchedWorld when its remote ticking runs). */
@Mixin(ParticleEngine.class)
public abstract class ParticleEngineTickStampMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void flyaway$stamp(CallbackInfo ci) {
        ParticleTickStamp.mark(Minecraft.getInstance().level);
    }
}
