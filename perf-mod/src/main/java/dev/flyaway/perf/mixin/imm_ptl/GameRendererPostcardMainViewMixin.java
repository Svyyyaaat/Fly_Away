package dev.flyaway.perf.mixin.imm_ptl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.imm_ptl.postcard.PostcardRenderer;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Portal postcards, main-view captures (docs/portal_postcards.md). Records the main view's matrices and, right after
 * the main LevelRenderer.renderLevel, lets PostcardRenderer capture the player's own view as the postcard of portals
 * whose destination is in front of the camera. Immersive Portals wraps the same call and runs its portal passes
 * after it; whichever order the two wraps nest in, the capture happens while the main target still holds the main
 * view's depth: if IP's portal passes already ran this frame the call here is skipped and the hook in
 * IrisCompatPostcardMixin (before the passes) has done it. Portal passes never go through GameRenderer.renderLevel.
 * Disable: {@code imm_ptl.postcards=false} or {@code mixin.imm_ptl.GameRendererPostcardMainViewMixin=false}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererPostcardMainViewMixin {
    @WrapOperation(
            method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/LevelRenderer;renderLevel(Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"
            )
    )
    private void flyaway$postcardMainView(LevelRenderer instance, DeltaTracker deltaTracker, boolean renderBlockOutline, Camera camera,
                                          GameRenderer gameRenderer, LightTexture lightTexture, Matrix4f modelView, Matrix4f projection,
                                          Operation<Void> original) {
        PostcardRenderer.onMainLevelMatrices(modelView, projection);
        original.call(instance, deltaTracker, renderBlockOutline, camera, gameRenderer, lightTexture, modelView, projection);
        PostcardRenderer.onMainViewFinished();
    }
}
