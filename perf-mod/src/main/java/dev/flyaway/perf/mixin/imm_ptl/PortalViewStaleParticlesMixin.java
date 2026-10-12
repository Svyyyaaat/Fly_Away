package dev.flyaway.perf.mixin.imm_ptl;

import dev.flyaway.perf.PerfConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.mixin.client.particle.IEParticle;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * Immersive Portals keeps one particle list for all dimensions and only TICKS particles of the dimension the player is
 * in (MixinParticleEngine.onTickParticle). Particles of other dimensions (left over from when you were there, or spawned
 * by IP's remote-world ticking) never move again but are still drawn in portal views of that dimension, interpolated
 * between their last two positions with a changing partial tick - they look frozen and jitter. User request: hide them.
 * During a portal pass we now skip particles that don't belong to the player's own dimension. Same-dimension portal
 * views keep their (ticking) particles. Disable: imm_ptl.hideFrozenPortalParticles=false.
 */
@Mixin(value = RenderStates.class, remap = false)
public abstract class PortalViewStaleParticlesMixin {
    private static final boolean ENABLED = PerfConfig.getBool("imm_ptl.hideFrozenPortalParticles", true);

    @Inject(method = "shouldRenderParticle", at = @At("RETURN"), cancellable = true)
    private static void flyaway$hideFrozen(Particle particle, CallbackInfoReturnable<Boolean> cir) {
        if (!ENABLED || !cir.getReturnValueZ() || !PortalRendering.isRendering()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && ((IEParticle) particle).portal_getWorld() != mc.player.level()) {
            cir.setReturnValue(false);
        }
    }
}
