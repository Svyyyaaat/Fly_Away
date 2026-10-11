package dev.flyaway.perf.mixin.vanilla.entity;

import dev.flyaway.perf.vanilla.entity.FrameStamp;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What: advances {@link FrameStamp#frame} at the start of every client frame (Minecraft.runTick HEAD).
 * Why: gives entity-render patches (e.g. compat.irons_spellbooks.CurioBaseItemEquippedCacheMixin) an exact,
 * allocation-free key for per-frame caches. Cost: one static increment per frame.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameStampMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void flyaway$stampFrame(boolean renderLevel, CallbackInfo ci) {
        FrameStamp.onRunTick();
    }
}
