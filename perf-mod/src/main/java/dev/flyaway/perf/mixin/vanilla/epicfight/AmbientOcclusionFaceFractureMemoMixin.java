package dev.flyaway.perf.mixin.vanilla.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.epicfight.FractureLightMemo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * What: serves the ~15 {@code level.getBlockState(pos)} calls of {@code AmbientOcclusionFace.calculate} (per AO face)
 * from {@link FractureLightMemo} while Epic Fight's FractureBlockRenderer tessellates a fracture block.
 * <p>
 * Why: 0.32% of the render thread in profile 76w5vCPcjb (Level.getChunk through Immersive Portals' client chunk map
 * + section palette for each sample). Block states cannot change during a render pass, so the memo (keyed by getter
 * and position, reset every pass) returns exactly what the getter returned earlier in the pass.
 * Wraps all getBlockState invocations of calculate; Sable's wrap of the getShade call in the same method is unrelated.
 * Outside fracture rendering: a static boolean check + the original call.
 * Disable: mixin.vanilla.AmbientOcclusionFaceFractureMemoMixin=false
 */
@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer$AmbientOcclusionFace")
public abstract class AmbientOcclusionFaceFractureMemoMixin {
    @WrapOperation(method = "calculate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/BlockAndTintGetter;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState flyaway$memoState(BlockAndTintGetter level, BlockPos pos, Operation<BlockState> original) {
        if (!FractureLightMemo.inFracture) return original.call(level, pos);
        FractureLightMemo.Entry e = FractureLightMemo.slot(level, pos.asLong());
        if (e == null) return original.call(level, pos);
        BlockState s = e.blockState;
        if (s != null) return s;
        s = original.call(level, pos);
        e.blockState = s;
        return s;
    }
}
