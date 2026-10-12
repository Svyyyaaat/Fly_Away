package dev.flyaway.perf.mixin.vanilla.epicfight;

import dev.flyaway.perf.epicfight.FractureLightMemo;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Opens / closes the render-pass scope of {@link FractureLightMemo}: every {@code LevelRenderer.renderLevel} call
 * (main pass, and each nested Immersive Portals pass) starts a new memo epoch at HEAD and ends it at RETURN, so a
 * memoised light/shade/state value is only reused inside the same pass - after that pass' light updates
 * (pollLightUpdates / runLightUpdates run right after HEAD, before any block entity is drawn).
 * Plain non-cancelling HEAD/RETURN injects; no behaviour of its own. Without this mixin the memo is never active.
 * Disable: mixin.vanilla.LevelRendererFracturePassMixin=false (turns the fracture memo off).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFracturePassMixin {
    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void flyaway$fracturePassEnter(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                           LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        FractureLightMemo.enterPass();
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void flyaway$fracturePassExit(DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera, GameRenderer gameRenderer,
                                          LightTexture lightTexture, Matrix4f frustumMatrix, Matrix4f projectionMatrix, CallbackInfo ci) {
        FractureLightMemo.exitPass();
    }
}
