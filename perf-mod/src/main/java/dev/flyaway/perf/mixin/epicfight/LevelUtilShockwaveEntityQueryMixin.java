package dev.flyaway.perf.mixin.epicfight;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.List;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yesman.epicfight.api.utils.LevelUtil;

/**
 * What: on the server, skip the per-ray entity query of {@code LevelUtil.spreadShockwave} when the enclosing
 * {@code circleSlamFracture(..., hurtEntities = false)} will never use its result.
 * <p>
 * Why: circleSlamFracture casts one shockwave ray per perimeter cell (~8 x radius rays) and every ray does
 * {@code level.getEntities(null, AABB)} only to collect the entities standing on fractured cells into
 * {@code entityBeingHit} - which circleSlamFracture reads only when {@code hurtEntities} is true. Callers that pass
 * false (NightFall's WeaponTrailGroundSplitter: up to 3 x density x sub-steps calls per tick while the trail is active,
 * Yamato / scythe / Damocles / Heavy Rain fractures, Avalon) pay for thousands of section scans per tick on the server
 * thread for nothing. The query has no side effects; with an empty list the per-cell loop over entities simply does
 * nothing, and entityBeingHit (discarded) stays empty. Block destruction, packets, client fracture creation and every
 * hurtEntities = true slam are untouched.
 * Disable: mixin.epicfight.LevelUtilShockwaveEntityQueryMixin=false
 */
@Mixin(value = LevelUtil.class, remap = false)
public abstract class LevelUtilShockwaveEntityQueryMixin {
    /** Thread currently inside a server-side circleSlamFracture(hurtEntities=false); null otherwise. */
    @Unique
    private static volatile Thread flyaway$skipEntityQueryThread;

    @Inject(method = "circleSlamFracture(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;DZZZ)Z",
            at = @At("HEAD"))
    private static void flyaway$enterSlam(LivingEntity caster, Level level, Vec3 center, double radius, boolean noSound, boolean noParticle,
                                          boolean hurtEntities, CallbackInfoReturnable<Boolean> cir) {
        // Not re-entrant in practice; a nested call (hurtEntities=true) simply overwrites, and RETURN clears.
        flyaway$skipEntityQueryThread = (!level.isClientSide && !hurtEntities) ? Thread.currentThread() : null;
    }

    @Inject(method = "circleSlamFracture(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/Vec3;DZZZ)Z",
            at = @At("RETURN"))
    private static void flyaway$exitSlam(LivingEntity caster, Level level, Vec3 center, double radius, boolean noSound, boolean noParticle,
                                         boolean hurtEntities, CallbackInfoReturnable<Boolean> cir) {
        if (flyaway$skipEntityQueryThread == Thread.currentThread()) flyaway$skipEntityQueryThread = null;
    }

    @WrapOperation(method = "spreadShockwave", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<Entity> flyaway$skipUnusedEntityQuery(Level level, Entity except, AABB box, Operation<List<Entity>> original) {
        if (flyaway$skipEntityQueryThread == Thread.currentThread()) return List.of();
        return original.call(level, except, box);
    }
}
