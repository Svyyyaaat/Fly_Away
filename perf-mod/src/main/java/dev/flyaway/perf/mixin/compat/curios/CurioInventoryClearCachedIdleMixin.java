package dev.flyaway.perf.mixin.compat.curios;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.common.capability.CurioInventory;
import top.theillusivec4.curios.common.capability.CurioInventoryCapability;
import top.theillusivec4.curios.common.inventory.CurioStacksHandler;

/**
 * What: return early from {@code CurioInventoryCapability.clearCachedSlotModifiers()} when no slot handler has a
 * cached modifier.
 *
 * <p>Why: Curios' {@code EntityTickEvent.Post} handler calls it for every entity with a curios inventory every tick
 * (both sides). It first allocates a {@code HashMultimap} and then asks each slot handler for
 * {@code getCachedModifiers()}, which returns a NEW {@code HashSet} copy of the handler's map, only to call
 * {@code isEmpty()} on it. With nothing cached (the normal state: the map is only filled from saved
 * {@code CachedModifiers} NBT on load and is normally emptied by the first tick after that) the method then does nothing else. Profile: {@code clearCachedSlotModifiers} 0.34% of the server
 * thread (most of it {@code HashSet.<init>}), plus the same on the client thread.
 *
 * <p>Fix: if every handler is exactly a {@code CurioStacksHandler} (not a subclass) whose private {@code cachedModifiers} map is empty,
 * the original's "flag" stays false, its multimap stays empty and the method has no effect, so return at HEAD.
 * Any other handler implementation, or any cached modifier, runs the original unchanged.
 *
 * <p>Disable: {@code mixin.compat.CurioInventoryClearCachedIdleMixin=false}
 */
@Mixin(value = CurioInventoryCapability.class, remap = false)
public abstract class CurioInventoryClearCachedIdleMixin {
    @Shadow @Final CurioInventory curioInventory;

    @Inject(method = "clearCachedSlotModifiers()V", at = @At("HEAD"), cancellable = true)
    private void flyaway$skipWhenNothingCached(CallbackInfo ci) {
        for (ICurioStacksHandler handler : this.curioInventory.asMap().values()) {
            if (handler == null || handler.getClass() != CurioStacksHandler.class) return; // exact class: no overridden getter
            if (!((CurioStacksHandlerCachedModsAccessor) handler).flyaway$cachedModifiers().isEmpty()) return;
        }
        ci.cancel();
    }
}
