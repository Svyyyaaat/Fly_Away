package dev.flyaway.perf.mixin.compat.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.flyaway.perf.compat.CameraTweaks;
import dev.flyaway.perf.compat.PortalCameraWalls;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import yesman.epicfight.api.client.camera.EpicFightCameraAPI;

/**
 * GAMEPLAY/CAMERA FIX (not a perf patch): Epic Fight's own TPS camera (EpicFightCameraAPI.setupCamera, TPS mode)
 * treats Immersive Portals portals as walls. It does its own 8-corner ClientLevel.clip from the eye to the shoulder
 * position (the only clip in setupCamera); each clip is wrapped like vanilla getMaxZoom. EF's lock-on camera and the
 * TPS zoom transition call camera.getMaxZoom, which CameraPortalWallMixin already covers.
 * Only active when Immersive Portals is loaded.
 * Disable: camera.portalsAreWalls=false or mixin.compat.EpicFightCameraPortalWallMixin=false
 */
@Mixin(value = EpicFightCameraAPI.class, remap = false)
public abstract class EpicFightCameraPortalWallMixin {
    @WrapOperation(method = "setupCamera",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/ClientLevel;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult flyaway$portalsAreWalls(ClientLevel level, ClipContext context, Operation<BlockHitResult> original,
                                                   @Local(argsOnly = true) Camera camera,
                                                   @Local(argsOnly = true) float partialTick) {
        BlockHitResult hit = original.call(level, context);
        if (!CameraTweaks.PORTALS_ARE_WALLS || !CameraTweaks.IMM_PTL || camera == null) return hit;
        Entity entity = camera.getEntity();
        if (entity == null) return hit;
        return PortalCameraWalls.clip(level, entity.getEyePosition(partialTick), context.getFrom(), context.getTo(), hit);
    }
}
