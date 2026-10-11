package dev.flyaway.perf.mixin.compat.ponder;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.IdentityHashMap;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementClient;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Catnip's PlacementClient.checkHelpers runs every client tick while the crosshair is on a block and, for both hands,
 * asks EVERY registered placement helper (Create + all addons: dozens) matchesItem(heldItem) =
 * getItemPredicate().test(stack). The predicates are built per call (Predicate.and chains, Registrate entry lookups).
 * Profile: checkHelpers 0.31% of the render thread, IPlacementHelper.matchesItem 0.19%.
 *
 * <p>Fix: memoise matchesItem per helper for an unchanged held stack (same item, count and components, checked with
 * ItemStack.matches against a private copy), one slot per hand. Entries expire after 20 ticks, so a predicate that
 * depends on something other than the stack is at most one second stale (none known: all helpers test the item).
 * Disable: mixin.compat.PlacementClientItemMatchCacheMixin=false
 */
@Mixin(value = PlacementClient.class, remap = false)
public abstract class PlacementClientItemMatchCacheMixin {
    @Unique private static final int FLYAWAY$MAX_AGE_TICKS = 20;
    @Unique private static final ItemStack[] flyaway$keys = {null, null};
    @Unique private static final int[] flyaway$born = {0, 0};
    @Unique @SuppressWarnings("unchecked")
    private static final IdentityHashMap<IPlacementHelper, Boolean>[] flyaway$results =
            new IdentityHashMap[]{new IdentityHashMap<>(), new IdentityHashMap<>()};
    @Unique private static int flyaway$tick;
    @Unique private static int flyaway$nextVictim;
    @Unique private static ItemStack flyaway$lastStack;
    @Unique private static int flyaway$lastStackTick;
    @Unique private static int flyaway$lastSlot;

    @Inject(method = "checkHelpers()V", at = @At("HEAD"))
    private static void flyaway$tick(CallbackInfo ci) {
        flyaway$tick++;
    }

    @WrapOperation(method = "checkHelpers()V",
            at = @At(value = "INVOKE", target = "Lnet/createmod/catnip/placement/IPlacementHelper;matchesItem(Lnet/minecraft/world/item/ItemStack;)Z"))
    private static boolean flyaway$cachedMatchesItem(IPlacementHelper helper, ItemStack stack, Operation<Boolean> original) {
        int slot = -1;
        // Same stack object as the previous call in this tick: the slot is already resolved (avoids N component compares).
        if (stack == flyaway$lastStack && flyaway$lastStackTick == flyaway$tick) slot = flyaway$lastSlot;
        for (int i = 0; i < 2 && slot < 0; i++) {
            ItemStack key = flyaway$keys[i];
            if (key != null && flyaway$tick - flyaway$born[i] < FLYAWAY$MAX_AGE_TICKS && ItemStack.matches(key, stack)) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            slot = flyaway$nextVictim;
            flyaway$nextVictim ^= 1;
            flyaway$keys[slot] = stack.copy();
            flyaway$born[slot] = flyaway$tick;
            flyaway$results[slot].clear();
        }
        flyaway$lastStack = stack;
        flyaway$lastStackTick = flyaway$tick;
        flyaway$lastSlot = slot;
        Boolean cached = flyaway$results[slot].get(helper);
        if (cached != null) return cached;
        boolean result = original.call(helper, stack);
        flyaway$results[slot].put(helper, result);
        return result;
    }
}
