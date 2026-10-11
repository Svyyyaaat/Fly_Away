package dev.flyaway.perf.mixin.compat.curios;

import dev.flyaway.perf.compat.curios.ItemCacheKeys;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.common.capability.CurioInventoryCapability;

/**
 * What: {@code CurioInventoryCapability.findFirstCurio(Item)} - use a memoized cache key instead of rebuilding it.
 * Why: the method runs {@code Curios.itemCacheKey(item.getDefaultInstance())} on EVERY call: it allocates a fresh
 * ItemStack (owo + accessories hook its component map with streams) and stream-concatenates the toString() of every
 * default component - only to look up Curios' own per-game-tick result cache. In the user's profile this key
 * building was ~0.65% of the render thread (Iron's Spellbooks asks "is the invisibility ring equipped?" from
 * Entity.isInvisibleTo for every entity Xaero's radar / name-tag code looks at; Relics and Gliders do similar).
 * Fix: same predicate, same key string (computed once per Item, see {@link ItemCacheKeys}), same 3-arg lookup.
 * Result is identical; Curios' cache semantics (per game time) are unchanged.
 */
@Mixin(value = CurioInventoryCapability.class, remap = false)
public abstract class CurioInventoryCapabilityKeyMixin {
    @Shadow
    public abstract Optional<SlotResult> findFirstCurio(Predicate<ItemStack> filter, String cacheKey);

    @Inject(method = "findFirstCurio(Lnet/minecraft/world/item/Item;)Ljava/util/Optional;", at = @At("HEAD"), cancellable = true)
    private void flyaway$cachedKey(Item item, CallbackInfoReturnable<Optional<SlotResult>> cir) {
        cir.setReturnValue(this.findFirstCurio(stack -> stack.getItem() == item, ItemCacheKeys.get(item)));
    }
}
