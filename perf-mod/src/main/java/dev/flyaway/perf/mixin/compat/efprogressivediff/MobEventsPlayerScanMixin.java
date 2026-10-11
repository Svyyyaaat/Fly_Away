package dev.flyaway.perf.mixin.compat.efprogressivediff;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.minhhjjj.efprogressivediff.event.MobEvents;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * What: answer EF Progressive Difficulty's "players near this mob" query from {@code level.players()} when no player
 * can possibly be in the box, instead of scanning every entity section of the box.
 *
 * <p>Why: {@code MobEvents.onMobFirstTick} is an {@code EntityTickEvent.Post} listener that runs for EVERY mob EVERY
 * tick until the mob has been scaled (persistent tag {@code efprogressivediff:difficulty_stored}). Scaling only
 * happens when a player is within {@code groupRadius} (128 in this pack's config), so every loaded mob farther than
 * that from all players re-runs {@code getDifficultyAround} each tick: {@code level.getEntitiesOfClass(
 * ServerPlayer.class, box inflated by 128)}, i.e. a walk over ~17x17 chunk columns of entity sections (c2me filtering
 * iterator, AVL subset iterators, ClassInstanceMultiMap lookups). Profile: 0.76% of the server thread
 * ({@code getDifficultyAround} 0.72%, almost all of it the section walk).
 *
 * <p>Fix: {@code Level.getEntitiesOfClass(cls, box)} returns the non-spectator entities of that class in accessible
 * (tracked) entity sections whose bounding box intersects {@code box}. Every such ServerPlayer is in
 * {@code ServerLevel.players()} (players are added/removed there in the same {@code onTrackingStart/End} callbacks
 * that add/remove them from the visible entity storage). So if no player of {@code level.players()} has a bounding
 * box intersecting {@code box}, the original result is empty and an empty list is returned (the caller only tests
 * {@code isEmpty()} and returns -1). Otherwise the original query runs unchanged (same list, same order).
 *
 * <p>Disable: {@code mixin.compat.MobEventsPlayerScanMixin=false}
 */
@Mixin(value = MobEvents.class, remap = false)
public abstract class MobEventsPlayerScanMixin {
    @WrapOperation(
            method = "getDifficultyAround(Lnet/minecraft/world/entity/Entity;I)D",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;getEntitiesOfClass(Ljava/lang/Class;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"))
    private static List<?> flyaway$skipEmptyScan(ServerLevel level, Class<?> cls, AABB box, Operation<List<?>> original) {
        // Sable's entity getter also queries the box transformed out of a sub-level plot; our plain box test can't
        // reproduce that, so boxes centred in plot space always take the original path (review hardening).
        if (cls == ServerPlayer.class && !dev.flyaway.perf.compat.SablePlotCheck.inPlot(level, box.getCenter())) {
            boolean any = false;
            for (ServerPlayer p : level.players()) {
                if (p.getBoundingBox().intersects(box)) { any = true; break; }
            }
            if (!any) return List.of();
        }
        return original.call(level, cls, box);
    }
}
