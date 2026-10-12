package dev.flyaway.perf.mixin.compat.shouldersurfing;

import com.github.exopandora.shouldersurfing.api.client.world.phys.BlockCollisionPredicate;
import com.github.exopandora.shouldersurfing.api.client.world.phys.IObjectPicker;
import com.github.exopandora.shouldersurfing.client.ShoulderSurfingCamera;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.flyaway.perf.compat.CameraTweaks;
import dev.flyaway.perf.compat.PortalCameraWalls;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GAMEPLAY/CAMERA FIX (not a perf patch): Shoulder Surfing ("free camera") treats Immersive Portals portals as walls.
 * SS computes its own camera distance in the static ShoulderSurfingCamera.maxZoom (8 corner rays from the eye to the
 * shoulder offset, IObjectPicker.clip per ray, nearest hit wins); it is used for every SS mode (all shoulder presets,
 * ssrcamerafixes' overhead preset, decoupled/coupled, aiming). Each clip is wrapped: a portal crossed before the
 * block hit is returned as the hit, so SS clamps the camera in front of the portal plane with its usual margin.
 * Sable redirects distanceTo in the same method (sub-level distances); no overlap with this wrap.
 * Only active when Immersive Portals is loaded.
 * Disable: camera.portalsAreWalls=false or mixin.compat.ShoulderSurfingPortalWallMixin=false
 */
@Mixin(value = ShoulderSurfingCamera.class, remap = false)
public abstract class ShoulderSurfingPortalWallMixin {
    @WrapOperation(method = "maxZoom",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/exopandora/shouldersurfing/api/client/world/phys/IObjectPicker;clip(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/level/ClipContext;Lcom/github/exopandora/shouldersurfing/api/client/world/phys/BlockCollisionPredicate;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private static BlockHitResult flyaway$portalsAreWalls(IObjectPicker picker, BlockGetter getter, ClipContext context,
                                                          BlockCollisionPredicate predicate, Operation<BlockHitResult> original,
                                                          @Local(argsOnly = true) Entity cameraEntity,
                                                          @Local(argsOnly = true) float partialTick) {
        BlockHitResult hit = original.call(picker, getter, context, predicate);
        if (!CameraTweaks.PORTALS_ARE_WALLS || !CameraTweaks.IMM_PTL || !(getter instanceof Level level) || cameraEntity == null) {
            return hit;
        }
        return PortalCameraWalls.clip(level, cameraEntity.getEyePosition(partialTick), context.getFrom(), context.getTo(), hit);
    }
}
