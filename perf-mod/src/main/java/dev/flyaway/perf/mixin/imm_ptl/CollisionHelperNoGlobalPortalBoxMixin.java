package dev.flyaway.perf.mixin.imm_ptl;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.collision.CollisionHelper;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * What: {@code CollisionHelper.updateCollidingPortalForWorld} runs every client tick (and every server tick on the
 * integrated server) over every entity of the level. For each non-portal entity it first computes
 * {@code getStretchedBoundingBox(entity)} and then tests that box only against the level's GLOBAL portals.
 *
 * <p>Why: with no global portals (the normal case: global portals are only used by world wrapping / dimension
 * stacking) the box is never read, but computing it costs two Vec3 + two AABB allocations and a
 * {@code LivingEntity.getScale()} attribute lookup (AttributeMap -> AttributeSupplier hash lookup, cache-missing on
 * each entity). Profile: 0.24% (2026-10-11) / 0.10% (2026-10-03) of the render thread, of which 0.19% is the
 * attribute lookup.
 *
 * <p>Fix: when the method's {@code globalPortals} local is empty, skip the computation and hand back a dummy box. The
 * inner loop over the (empty) list never runs, so the dummy is never read. {@code getStretchedBoundingBox} has no
 * side effects (position/velocity reads, an attribute read and AABB arithmetic), so skipping it is invisible. With
 * any global portal present the original call is made. Portal entities take the other branch and are unaffected.
 * Disable: {@code mixin.imm_ptl.CollisionHelperNoGlobalPortalBoxMixin=false}.
 */
@Mixin(value = CollisionHelper.class, remap = false)
public abstract class CollisionHelperNoGlobalPortalBoxMixin {
    @Unique
    private static final AABB FLYAWAY$UNUSED = new AABB(0, 0, 0, 0, 0, 0);

    @WrapOperation(
            method = "updateCollidingPortalForWorld",
            at = @At(value = "INVOKE",
                    target = "Lqouteall/imm_ptl/core/collision/CollisionHelper;getStretchedBoundingBox(Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/phys/AABB;"))
    private static AABB flyaway$skipWhenNoGlobalPortals(Entity entity, Operation<AABB> original,
            @Local(ordinal = 0) List<Portal> globalPortals) {
        if (globalPortals != null && globalPortals.isEmpty()) return FLYAWAY$UNUSED;
        return original.call(entity);
    }
}
