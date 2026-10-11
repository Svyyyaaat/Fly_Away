package dev.flyaway.perf.mixin.compat.irons_spellbooks;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.flyaway.perf.vanilla.entity.FrameStamp;
import io.redspace.ironsspellbooks.item.curios.CurioBaseItem;
import java.lang.ref.WeakReference;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * What: per-frame memo of {@code CurioBaseItem.isEquippedBy(entity)} on the client thread.
 * Why: Iron's Spellbooks injects into {@code Entity.isInvisibleTo(Player)} and asks
 * {@code INVISIBILITY_RING.isEquippedBy(player)} on every call. Xaero's minimap radar calls isInvisibleTo for
 * every tracked entity every update, name-tag checks (shouldShowName) and Gliders' layer for every rendered
 * entity - always with the same local player. Each call does a capability lookup, Optional chain and a
 * Curios cache lookup; profile: isEquippedBy = 0.95% of the render thread (~0.65% of it is fixed by
 * compat.curios.CurioInventoryCapabilityKeyMixin, this one removes the rest).
 * Fix: on the client thread only, for the same (item, entity) within one client epoch (see {@link FrameStamp}: bumped at frame start, around the client tick and after keybinds),
 * return the previous answer. Curios inventories only change through packets/client tick/input, all outside the
 * render part of a frame, and Curios itself already caches this lookup per game tick - so the result is
 * identical. Server thread calls (spell logic) are never cached.
 * Saving: ~0.2-0.3% render thread. Disable: mixin.compat.CurioBaseItemEquippedCacheMixin=false
 */
@Mixin(value = CurioBaseItem.class, remap = false)
public abstract class CurioBaseItemEquippedCacheMixin {
    @Unique
    private WeakReference<LivingEntity> flyaway$lastEntity;
    @Unique
    private long flyaway$lastFrame = Long.MIN_VALUE;
    @Unique
    private boolean flyaway$lastResult;

    @WrapMethod(method = "isEquippedBy")
    private boolean flyaway$frameCache(LivingEntity entity, Operation<Boolean> original) {
        if (entity == null || !FrameStamp.onClientThread() || !entity.level().isClientSide()) {
            return original.call(entity);
        }
        long frame = FrameStamp.epoch;
        WeakReference<LivingEntity> ref = this.flyaway$lastEntity;
        if (this.flyaway$lastFrame == frame && ref != null && ref.get() == entity) {
            return this.flyaway$lastResult;
        }
        boolean result = original.call(entity);
        if (ref == null || ref.get() != entity) {
            this.flyaway$lastEntity = new WeakReference<>(entity);
        }
        this.flyaway$lastFrame = frame;
        this.flyaway$lastResult = result;
        return result;
    }
}
