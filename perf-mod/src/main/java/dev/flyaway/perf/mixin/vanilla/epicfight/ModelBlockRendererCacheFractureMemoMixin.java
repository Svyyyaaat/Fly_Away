package dev.flyaway.perf.mixin.vanilla.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.epicfight.FractureLightMemo;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * What: serves the two expensive calls of the smooth-lighting sample cache ({@code ModelBlockRenderer.Cache}) -
 * {@code LevelRenderer.getLightColor(level, state, pos)} and {@code state.getShadeBrightness(level, pos)} - from
 * {@link FractureLightMemo} while (and only while) Epic Fight's FractureBlockRenderer tessellates a fracture block.
 * <p>
 * Why: the render thread's Cache is disabled (vanilla enables it only during chunk meshing), so every AO face of
 * every fracture block re-reads light (light engine sky + block, LambDynLights' dynamic light, micimmptl's foreign
 * skylight) and shade brightness: 4.36% + 0.31% of the render thread in profile 76w5vCPcjb. Within one render pass
 * these answers cannot change; keyed by (getter, pos, state identity) the memo returns exactly what the original call
 * returned earlier in the same pass.
 * <p>
 * Outside fracture rendering the wrapper is a static boolean check + the original call. The vanilla Cache logic
 * around it (enabled/LRU) is untouched. Sable only adds a field to Cache; no other mod touches these methods.
 * Disable: mixin.vanilla.ModelBlockRendererCacheFractureMemoMixin=false
 */
@Mixin(targets = "net.minecraft.client.renderer.block.ModelBlockRenderer$Cache")
public abstract class ModelBlockRendererCacheFractureMemoMixin {
    @WrapOperation(method = "getLightColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;getLightColor(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)I"))
    private int flyaway$memoLight(BlockAndTintGetter level, BlockState state, BlockPos pos, Operation<Integer> original) {
        if (!FractureLightMemo.inFracture) return original.call(level, state, pos);
        FractureLightMemo.Entry e = FractureLightMemo.slot(level, pos.asLong());
        if (e == null) return original.call(level, state, pos);
        if (state != null && e.lightArg == state) return e.light;
        int v = original.call(level, state, pos);
        e.lightArg = state;
        e.light = v;
        return v;
    }

    @WrapOperation(method = "getShadeBrightness", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getShadeBrightness(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float flyaway$memoShade(BlockState state, BlockGetter level, BlockPos pos, Operation<Float> original) {
        if (!FractureLightMemo.inFracture || !(level instanceof BlockAndTintGetter getter)) return original.call(state, level, pos);
        FractureLightMemo.Entry e = FractureLightMemo.slot(getter, pos.asLong());
        if (e == null) return original.call(state, level, pos);
        if (state != null && e.shadeArg == state) return e.shade;
        float v = original.call(state, level, pos);
        e.shadeArg = state;
        e.shade = v;
        return v;
    }
}
