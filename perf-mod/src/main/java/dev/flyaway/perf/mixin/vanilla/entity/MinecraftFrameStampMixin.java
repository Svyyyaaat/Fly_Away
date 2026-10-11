package dev.flyaway.perf.mixin.vanilla.entity;

import dev.flyaway.perf.vanilla.entity.FrameStamp;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What: advances {@link FrameStamp#epoch} at the start of every client frame, around the client tick and right after
 * keybind handling - every point where the client thread may change equipment/inventories.
 * Why: gives entity-render patches (compat.irons_spellbooks / compat.vc_gliders caches) an exact, allocation-free
 * key for "equipped item" caches. Cost: a few static increments per frame.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftFrameStampMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void flyaway$stampFrame(boolean renderLevel, CallbackInfo ci) {
        FrameStamp.bump();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void flyaway$stampTickStart(CallbackInfo ci) {
        FrameStamp.bump();
    }

    // at the end of handleKeybinds itself (not at its call site in tick(): Simply Swords @Redirects that call)
    @Inject(method = "handleKeybinds", at = @At("RETURN"))
    private void flyaway$stampAfterKeybinds(CallbackInfo ci) {
        FrameStamp.bump();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void flyaway$stampTickEnd(CallbackInfo ci) {
        FrameStamp.bump();
    }
}
