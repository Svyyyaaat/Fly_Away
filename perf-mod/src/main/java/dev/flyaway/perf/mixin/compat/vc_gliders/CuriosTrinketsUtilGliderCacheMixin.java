package dev.flyaway.perf.mixin.compat.vc_gliders;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.flyaway.perf.vanilla.entity.FrameStamp;
import java.lang.ref.WeakReference;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.venturecraft.gliders.common.compat.trinket.CuriosTrinketsUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * What: short-lived memo of Gliders' {@code CuriosTrinketsUtil.getFirstFoundGlider(entity)} on the client thread.
 * Why: Gliders asks "which glider does this entity wear?" from Block.animateTick (every animated block, every
 * tick), RenderLevelStageEvent (every stage of every pass, every frame), its player layer, the ItemInHandLayer hook
 * and player tick. Each call checks the chest slot and then 3 Curios slots (capability lookup + list allocation
 * per slot). Profile: 0.39% of the render thread for this method alone (Gliders total 0.58%).
 * Fix: on the client thread only, if the same entity is asked again within the same client epoch
 * (see {@link FrameStamp}: bumped at frame start, around the client tick and after keybinds - i.e. no inventory
 * can change in between) AND its chest slot still returns the very same ItemStack instance, return the previous
 * result (the same live ItemStack reference the original would return). The chest-stack check keeps the result
 * exact even when Accessories swaps in a cosmetic chest item during entity rendering.
 * Server-thread calls are never cached.
 * Saving: ~0.3% render thread. Disable: mixin.compat.CuriosTrinketsUtilGliderCacheMixin=false
 */
@Mixin(value = CuriosTrinketsUtil.class, remap = false)
public abstract class CuriosTrinketsUtilGliderCacheMixin {
    @Unique
    private WeakReference<LivingEntity> flyaway$entity;
    @Unique
    private long flyaway$epoch = Long.MIN_VALUE;
    @Unique
    private ItemStack flyaway$chest;
    @Unique
    private ItemStack flyaway$result;

    @WrapMethod(method = "getFirstFoundGlider")
    private ItemStack flyaway$cache(LivingEntity entity, Operation<ItemStack> original) {
        if (entity == null || !FrameStamp.onClientThread() || !entity.level().isClientSide()) {
            return original.call(entity);
        }
        long epoch = FrameStamp.epoch;
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        WeakReference<LivingEntity> ref = this.flyaway$entity;
        if (this.flyaway$epoch == epoch && chest == this.flyaway$chest && ref != null && ref.get() == entity) {
            return this.flyaway$result;
        }
        ItemStack result = original.call(entity);
        if (ref == null || ref.get() != entity) {
            this.flyaway$entity = new WeakReference<>(entity);
        }
        this.flyaway$epoch = epoch;
        this.flyaway$chest = chest;
        this.flyaway$result = result;
        return result;
    }
}
