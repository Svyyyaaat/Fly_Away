package dev.flyaway.perf.mixin.vanilla.portals;

import dev.flyaway.perf.imm_ptl.PortalFrameScope;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Maintains {@link PortalFrameScope} (the "world is frozen, we are rendering" window used by per-frame caches such as
 * {@code mixin.imm_ptl.SubLevelStraddlePortalCacheMixin}): open/close around {@code GameRenderer.renderLevel}
 * (depth-counted, Immersive Portals re-enters it for every portal view) and a hard reset at the start of every
 * {@code GameRenderer.render}. Plain non-cancelling HEAD/RETURN injects - no interaction with the other mods that
 * wrap these methods. Cost: negligible.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFrameScopeMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void flyaway$resetFrameScope(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        PortalFrameScope.resetForNewFrame();
    }

    /** Also closes the window after the frame, even if some other mod cancelled renderLevel before its RETURN. */
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("RETURN"))
    private void flyaway$closeFrameScope(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        PortalFrameScope.resetForNewFrame();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void flyaway$enterRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        PortalFrameScope.enterRenderLevel();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At("RETURN"))
    private void flyaway$exitRenderLevel(DeltaTracker deltaTracker, CallbackInfo ci) {
        PortalFrameScope.exitRenderLevel();
    }
}
