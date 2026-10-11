package dev.flyaway.perf.mixin.compat.curios;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.flyaway.perf.compat.CuriosItemKeys;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import top.theillusivec4.curios.common.capability.CurioInventoryCapability;

/**
 * CurioInventoryCapability.findFirstCurio(Item) / findCurios(Item) compute
 * {@code Curios.itemCacheKey(item.getDefaultInstance())} on every call: a new default ItemStack (component map copy,
 * owo/accessories hooks) plus a String built from toString() of all default components. Curios then uses that string
 * only as the key of its own per-game-tick result cache.
 *
 * In this pack Iron's Spellbooks hooks Entity.isInvisibleTo(player) -> CurioBaseItem.isEquippedBy -> findFirstCurio(
 * INVISIBILITY_RING) for every entity Xaero's radar scans each tick and every mob name-tag check each frame:
 * findFirstCurio 0.95% of the render thread, of which itemCacheKey 0.48% + getDefaultInstance 0.13%.
 *
 * Fix: both arguments are a pure function of the Item, so we skip getDefaultInstance (return EMPTY placeholder) once
 * the key is memoised and return the memoised key from itemCacheKey. The key string is identical, so Curios' own cache
 * and results are unchanged. Expected: ~0.5-0.6% render thread (plus the same per call on the server thread).
 */
@Mixin(value = CurioInventoryCapability.class, remap = false)
public abstract class CurioInventoryItemKeyMixin {
    @WrapOperation(
            method = {"findFirstCurio(Lnet/minecraft/world/item/Item;)Ljava/util/Optional;",
                      "findCurios(Lnet/minecraft/world/item/Item;)Ljava/util/List;"},
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;getDefaultInstance()Lnet/minecraft/world/item/ItemStack;"),
            require = 2)
    private ItemStack flyaway$skipDefaultInstance(Item item, Operation<ItemStack> original) {
        // The stack is only passed to Curios.itemCacheKey, which we replace below with the memoised key.
        return ItemStack.EMPTY;
    }

    @WrapOperation(
            method = {"findFirstCurio(Lnet/minecraft/world/item/Item;)Ljava/util/Optional;",
                      "findCurios(Lnet/minecraft/world/item/Item;)Ljava/util/List;"},
            at = @At(value = "INVOKE", target = "Ltop/theillusivec4/curios/Curios;itemCacheKey(Lnet/minecraft/world/item/ItemStack;)Ljava/lang/String;"),
            require = 2)
    private String flyaway$memoKey(ItemStack ignored, Operation<String> original, @Local(argsOnly = true) Item item) {
        return CuriosItemKeys.key(item);
    }
}
