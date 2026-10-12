package dev.flyaway.perf.mixin.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.flyaway.perf.epicfight.FractureLightMemo;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.client.renderer.blockentity.FractureBlockRenderer;

/**
 * What: marks Epic Fight's per-frame fracture block tessellation so the smooth-lighting world reads inside it are
 * served from the per-pass memo ({@link FractureLightMemo}, used by the vanilla.epicfight Cache / AO mixins).
 * <p>
 * Why: {@code FractureBlockRenderer.render} redraws the original block model of every fracture block every frame via
 * {@code renderBreakingTexture(originalState, pos.above(), level, ...)} into {@code RenderType.cutout()} - a full
 * {@code tesselateBlock} with smooth lighting (checkSides = true). The light/shade/state lookups of the AO code are
 * 5.0% of the render thread (profile 76w5vCPcjb, whole renderer 7.13%); neighbouring fracture blocks share nearly all
 * sample positions, and nothing in the world changes during a pass, so each (position, state) needs to be read once
 * per pass instead of ~50 times per block. With a large slam (hundreds of fracture blocks) this per-frame cost is what
 * makes the client stall.
 * <p>
 * The wrapped call itself (and the aeronautics / colorwheel wrappers inside it) runs unchanged; we only set a flag
 * around it. Output identical by construction (see FractureLightMemo for the exactness argument).
 * Expected saving: ~4% of the render thread while fracture blocks exist (scales with their number).
 * Disable: mixin.epicfight.FractureBlockRendererLightMemoMixin=false
 */
@Mixin(value = FractureBlockRenderer.class, remap = false)
public abstract class FractureBlockRendererLightMemoMixin {
    @WrapOperation(
            method = "render(Lyesman/epicfight/world/level/block/entity/FractureBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/neoforged/neoforge/client/model/data/ModelData;)V"))
    private void flyaway$memoFractureLight(BlockRenderDispatcher dispatcher, BlockState state, BlockPos pos, BlockAndTintGetter level,
                                           PoseStack poseStack, VertexConsumer consumer, ModelData modelData, Operation<Void> original) {
        boolean prev = FractureLightMemo.inFracture;
        FractureLightMemo.inFracture = true;
        try {
            original.call(dispatcher, state, pos, level, poseStack, consumer, modelData);
        } finally {
            FractureLightMemo.inFracture = prev;
        }
    }
}
