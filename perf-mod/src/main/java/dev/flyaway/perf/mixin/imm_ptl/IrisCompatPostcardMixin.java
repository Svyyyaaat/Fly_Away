package dev.flyaway.perf.mixin.imm_ptl;

import dev.flyaway.perf.imm_ptl.postcard.PostcardRenderer;
import java.util.List;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatibilityPortalRenderer;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * Portal postcards (FEATURE, not a perf patch; docs/portal_postcards.md). With Iris shaders Immersive Portals uses
 * IrisCompatibilityPortalRenderer (micimmptl forces its compatibility mode), which renders a single portal layer:
 * {@code onBeforeHandRendering} and {@code doRenderPortal} do nothing while a portal is being rendered, so a portal
 * seen through another portal shows nothing.
 * <ul>
 *   <li>{@code onBeforeHandRendering} HEAD, only while a layer-1 portal pass ends (the exact spot where IP would
 *       recurse into layer 2 without Iris; the pass image incl. Iris composites/final is in the main target here):
 *       capture the outer portal's postcard and draw postcards of nested portals into the main target. IP then copies
 *       the outer portal's area of the main target into its deferred buffer, so the outer clipping is automatic.</li>
 *   <li>{@code onBeforeHandRendering}, main pass, before the portal passes: capture the player's own view as the
 *       postcard of portals whose destination is right in front of the camera (see
 *       {@link dev.flyaway.perf.mixin.imm_ptl.GameRendererPostcardMainViewMixin}).</li>
 *   <li>{@code doRenderPortal} after {@code drawPortalAreaWithFramebuffer} (main pass, portal drawn live): fade the
 *       postcard out over the live view when the portal was a postcard a moment ago.</li>
 * </ul>
 * Plain non-cancelling injects; coexists with micimmptl's IrisCompatIdleSkipMixin (HEAD, cancels only outside
 * portal passes), IrisCompatPortalScissorMixin (push/pop layer) and IrisCompatPortalViewReuseMixin (HEAD/pop layer).
 * CPU cost when no nested portal is visible: one portal list query per layer-1 pass and one small GPU copy every
 * {@code imm_ptl.postcards.refreshFrames} frames per directly visible portal.
 * Disable: {@code imm_ptl.postcards=false} or {@code mixin.imm_ptl.IrisCompatPostcardMixin=false}.
 */
@Mixin(value = IrisCompatibilityPortalRenderer.class, remap = false)
public abstract class IrisCompatPostcardMixin extends PortalRenderer implements PostcardRenderer.PortalLister {
    @Shadow
    public boolean isDebugMode;

    @Override
    public List<Portal> flyaway$portalsToRender(Matrix4f modelView) {
        return this.getPortalsToRender(modelView);
    }

    @Inject(method = "onBeforeHandRendering(Lorg/joml/Matrix4f;)V", at = @At("HEAD"))
    private void flyaway$postcardsAtPassEnd(Matrix4f modelView, CallbackInfo ci) {
        if (!this.isDebugMode && PortalRendering.isRendering()) {
            PostcardRenderer.onPortalPassEnd(this, modelView);
        }
    }

    /** Main pass, before the portal passes overwrite the main target's depth: capture the player's own view. */
    @Inject(
            method = "onBeforeHandRendering(Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lqouteall/imm_ptl/core/compat/iris_compatibility/IPIrisHelper;newCopyDepthStencil(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/pipeline/RenderTarget;)V"
            )
    )
    private void flyaway$postcardsMainView(Matrix4f modelView, CallbackInfo ci) {
        if (!this.isDebugMode) PostcardRenderer.onMainViewFinished();
    }

    @Inject(
            method = "doRenderPortal(Lqouteall/imm_ptl/core/portal/Portal;Lorg/joml/Matrix4f;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lqouteall/imm_ptl/core/render/MyRenderHelper;drawPortalAreaWithFramebuffer(Lqouteall/imm_ptl/core/portal/Portal;Lcom/mojang/blaze3d/pipeline/RenderTarget;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void flyaway$postcardFadeOut(Portal portal, Matrix4f modelView, CallbackInfo ci) {
        PostcardRenderer.onLivePortalDrawn(portal, modelView);
    }
}
