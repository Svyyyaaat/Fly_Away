package dev.flyaway.perf.mixin.vanilla.epicfight;

import dev.flyaway.perf.epicfight.FractureLightMemo;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Safety reset for {@link FractureLightMemo}: at the start of every frame the pass scope is force-closed and the memo
 * emptied, so even if some mod cancelled {@code LevelRenderer.renderLevel} after our HEAD inject (unbalanced depth),
 * no memoised value can survive into a later frame (ticks / packets / light updates happen between frames).
 * Plain non-cancelling HEAD inject.
 * Disable: mixin.vanilla.GameRendererFractureFrameMixin=false (only removes the safety net).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFractureFrameMixin {
    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"))
    private void flyaway$fractureNewFrame(DeltaTracker deltaTracker, boolean renderLevel, CallbackInfo ci) {
        FractureLightMemo.newFrame();
    }
}
