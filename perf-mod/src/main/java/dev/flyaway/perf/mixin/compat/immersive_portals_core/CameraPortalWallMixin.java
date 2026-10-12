package dev.flyaway.perf.mixin.compat.immersive_portals_core;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.compat.CameraTweaks;
import dev.flyaway.perf.compat.PortalCameraWalls;
import net.minecraft.client.Camera;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * GAMEPLAY/CAMERA FIX (not a perf patch): vanilla third person (F5 back and front) treats Immersive Portals portals
 * as walls. Camera.getMaxZoom casts 8 corner rays (0.1 offset) from the eye backwards and clamps the distance to the
 * nearest block hit; each ray's BlockGetter.clip is wrapped so a portal crossed before the block counts as the hit.
 * Without this the camera slips behind the portal plane and IP's cross-portal view renders the destination world
 * from the wrong side ("view goes crazy" next to portals).
 * Also covers Epic Fight's lock-on camera (it calls camera.getMaxZoom). Sable's sub-level view camera cancels
 * getMaxZoom at HEAD and keeps its own clamp.
 * Disable: camera.portalsAreWalls=false or mixin.compat.CameraPortalWallMixin=false
 */
@Mixin(Camera.class)
public abstract class CameraPortalWallMixin {
    @Shadow private Vec3 position;

    @WrapOperation(method = "getMaxZoom(F)F",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/BlockGetter;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"))
    private BlockHitResult flyaway$portalsAreWalls(BlockGetter getter, ClipContext context, Operation<BlockHitResult> original) {
        BlockHitResult hit = original.call(getter, context);
        if (!CameraTweaks.PORTALS_ARE_WALLS || !(getter instanceof Level level)) return hit;
        // this.position is still the eye position here: setup() only moves the camera after getMaxZoom returns.
        return PortalCameraWalls.clip(level, this.position, context.getFrom(), context.getTo(), hit);
    }
}
