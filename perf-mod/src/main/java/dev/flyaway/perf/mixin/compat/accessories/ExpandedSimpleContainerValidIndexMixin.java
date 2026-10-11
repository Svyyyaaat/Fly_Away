package dev.flyaway.perf.mixin.compat.accessories;

import io.wispforest.accessories.impl.ExpandedSimpleContainer;
import net.minecraft.world.SimpleContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What: answer {@code ExpandedSimpleContainer.validIndex(slot)} for in-range slots without building its debug string.
 *
 * <p>Why: Accessories' {@code validIndex} (called by every {@code getItem}/{@code setItem}/{@code removeItem} on an
 * accessory container) unconditionally builds {@code "Container: " + name + ", "} via a string concat, although the
 * string is only used when the index is invalid AND the game runs in a development environment. Accessory containers
 * are read for every entity with slots every tick on both sides (the server tick handler, the client
 * {@code getItemBySlot} cosmetic hook used by every equipment render, Aether/Artifacts/Reliquary/SophisticatedCore
 * accessory scans), so this is one throw-away String + byte[] per slot read.
 *
 * <p>Fix: for {@code 0 <= slot < getContainerSize()} the original returns {@code true} with no other effect, so
 * return it at HEAD; out-of-range calls run the original (same logging). Same result on every input.
 *
 * <p>Disable: {@code mixin.compat.ExpandedSimpleContainerValidIndexMixin=false}
 */
@Mixin(value = ExpandedSimpleContainer.class, remap = false)
public abstract class ExpandedSimpleContainerValidIndexMixin {
    @Inject(method = "validIndex(I)Z", at = @At("HEAD"), cancellable = true)
    private void flyaway$validFast(int slot, CallbackInfoReturnable<Boolean> cir) {
        if (slot >= 0 && slot < ((SimpleContainer) (Object) this).getContainerSize()) cir.setReturnValue(true);
    }
}
