package dev.flyaway.perf.mixin.compat.shouldersurfing;

import com.github.exopandora.shouldersurfing.api.math.Vec2f;
import com.github.exopandora.shouldersurfing.client.ShoulderSurfingCamera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Access to Shoulder Surfing's camera aim rotation (without the free-look offset), used by
 * compat.tp_shooting.TaczRecoilShoulderSurfingMixin to add TACZ recoil the way the public setXRot/setYRot would,
 * but without discarding an active free-look offset.
 */
@Mixin(value = ShoulderSurfingCamera.class, remap = false)
public interface ShoulderSurfingCameraAccessor {
    @Accessor("rotation")
    Vec2f flyaway$getRotation();

    @Accessor("rotation")
    void flyaway$setRotation(Vec2f rotation);
}
