package dev.flyaway.perf.mixin.compat.accessories;

import dev.flyaway.perf.compat.AccessoriesIdleTick;
import io.wispforest.accessories.impl.AccessoriesEventHandler;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What: skip the body of Accessories' per-entity tick handler when every accessory slot of the entity is empty and
 * nothing is pending, doing only the (cheap) parts of it that still have an effect.
 *
 * <p>Why: {@code AccessoriesEventHandler.onLivingEntityTick} runs from NeoForge's {@code EntityTickEvent.Pre} for
 * every living entity with accessory slots. Artifacts' {@code accessories/entity/slots.json} gives zombies, husks,
 * drowned, skeletons, strays, bogged, wither skeletons, piglins and ghasts five slots each, plus the player's
 * twelve. Each call allocates two HashMaps and two {@code AccessoryAttributeBuilder}s (HashMap + LinkedHashMultimap
 * + HashSet each), and for every slot a {@code SlotReference} and a {@code slotName + "/" + i} string, then
 * {@code ImmutableMap.copyOf}, {@code updateContainers()} and four {@code getHolder()} round trips (each an
 * attachment lookup + an {@code EntitySlotLoader} map lookup). Profile (local test world, server thread):
 * 2.89% of the server thread, the single most expensive EntityTickEvent listener.
 *
 * <p>Fix: {@link AccessoriesIdleTick#tryIdleTick} proves, without side effects, that the original call would not
 * change anything (all slot stacks empty and, on the server, every stack/cosmetic stack {@code ItemStack.matches} its
 * remembered previous stack - the original's own change test; no container flagged, no pending updates or invalid
 * stacks, holder already initialised) and then performs only what remains observable: the
 * {@code AccessoriesCapability.get} call and, on the server, the {@code ContainersChangeCallback} post with an empty
 * map. Otherwise the original method runs unchanged. Expected: ~2% of the server thread in mob-heavy areas.
 *
 * <p>Disable: {@code mixin.compat.AccessoriesEntityTickIdleMixin=false}
 */
@Mixin(value = AccessoriesEventHandler.class, remap = false)
public abstract class AccessoriesEntityTickIdleMixin {
    @Inject(method = "onLivingEntityTick(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), cancellable = true)
    private static void flyaway$idleTick(LivingEntity entity, CallbackInfo ci) {
        if (AccessoriesIdleTick.tryIdleTick(entity)) ci.cancel();
    }
}
