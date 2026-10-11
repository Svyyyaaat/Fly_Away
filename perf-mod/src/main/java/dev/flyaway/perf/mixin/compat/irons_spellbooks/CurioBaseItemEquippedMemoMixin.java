package dev.flyaway.perf.mixin.compat.irons_spellbooks;

import com.mojang.blaze3d.systems.RenderSystem;
import io.redspace.ironsspellbooks.item.curios.CurioBaseItem;
import java.lang.ref.WeakReference;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Iron's Spellbooks injects into Entity.isInvisibleTo(player): "if INVISIBILITY_RING.isEquippedBy(player) return false".
 * The client calls isInvisibleTo(localPlayer) for every entity Xaero's radar scans each tick (RadarStateUpdater, 0.64%
 * of the render thread) and for every name-tag / glider-layer check each frame (another ~0.4%). Each call does a
 * capability lookup (new CurioInventoryCapability wrapper), a Guava cache probe and Optional plumbing - always for
 * the same (player, ring) pair.
 *
 * Curios' own findFirstCurio result is already cached per (entity, item, level game time) and never invalidated
 * within a game tick, so remembering the last (entity, gameTime) -> result for this item returns exactly what Curios
 * would. Memo is only used on the render thread (the integrated server thread calls this too and keeps the
 * unmodified path). Complements compat.curios.CurioInventoryItemKeyMixin. Expected: ~0.3% render thread.
 */
@Mixin(value = CurioBaseItem.class, remap = false)
public abstract class CurioBaseItemEquippedMemoMixin {
    @Unique
    private WeakReference<LivingEntity> flyaway$memoEntity;
    @Unique
    private long flyaway$memoTime = Long.MIN_VALUE;
    @Unique
    private boolean flyaway$memoResult;

    @Inject(method = "isEquippedBy", at = @At("HEAD"), cancellable = true)
    private void flyaway$memoHit(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null || !RenderSystem.isOnRenderThread()) return;
        WeakReference<LivingEntity> ref = this.flyaway$memoEntity;
        if (ref != null && ref.get() == entity && this.flyaway$memoTime == entity.level().getGameTime()) {
            cir.setReturnValue(this.flyaway$memoResult);
        }
    }

    @Inject(method = "isEquippedBy", at = @At("RETURN"))
    private void flyaway$memoStore(LivingEntity entity, CallbackInfoReturnable<Boolean> cir) {
        if (entity == null || !RenderSystem.isOnRenderThread()) return;
        WeakReference<LivingEntity> ref = this.flyaway$memoEntity;
        if (ref == null || ref.get() != entity) this.flyaway$memoEntity = new WeakReference<>(entity);
        this.flyaway$memoTime = entity.level().getGameTime();
        this.flyaway$memoResult = cir.getReturnValueZ();
    }
}
