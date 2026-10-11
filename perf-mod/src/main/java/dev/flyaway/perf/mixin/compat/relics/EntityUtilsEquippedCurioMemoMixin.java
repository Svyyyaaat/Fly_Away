package dev.flyaway.perf.mixin.compat.relics;

import dev.flyaway.perf.vanilla.entity.FrameStamp;
import it.hurts.sskirillss.relics.utils.EntityUtils;
import java.lang.ref.WeakReference;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What: memoise {@code relics EntityUtils.findEquippedCurio(entity, item)} on the client thread for the current
 * {@link FrameStamp#epoch}.
 *
 * <p>Why: Relics hooks {@code BlockStateBase.getCollisionShape(level, pos, ctx)} at HEAD (Glitchy/Leafy Mantle).
 * For every AIR block queried with an entity collision context it calls {@code findEquippedCurio(player,
 * GLITCHY_MANTLE)}, which does a Curios capability lookup (a new wrapper object), Optional plumbing and a linear scan
 * of every equipped curio slot. Shoulder Surfing's camera ray ({@code ObjectPicker.clip}, every frame) walks dozens
 * of air blocks, and the Reliquified Artifacts fog/night-vision hooks call it again every frame. Profile:
 * {@code findEquippedCurio} 0.64% (2026-10-03, 0.50% from the air-collision hook alone) and 0.19% (2026-10-11)
 * of the render thread.
 *
 * <p>Fix: on the client thread, remember the last answers for one entity (up to 4 items) until the next
 * {@link FrameStamp} bump. FrameStamp is bumped at every point where the client thread can change inventories
 * (frame start, before and after the client tick, after keybinds), and the integrated server never touches
 * client-side entities. So between two bumps the curio slots hold the same stacks and a fresh scan returns the same
 * {@code ItemStack} object. A remembered non-empty hit is also re-validated ({@code !isEmpty()} and same item)
 * before it is reused, so a stack mutated in place still takes the original path. Non-player entities (for which Relics
 * returns EMPTY immediately), other threads (integrated server) and a disabled FrameStamp always take the original
 * path.
 * Disable: {@code mixin.compat.EntityUtilsEquippedCurioMemoMixin=false}.
 */
@Mixin(value = EntityUtils.class, remap = false)
public abstract class EntityUtilsEquippedCurioMemoMixin {
    @Unique private static final int FLYAWAY$N = 4;
    @Unique private static WeakReference<Entity> flyaway$entity;
    @Unique private static long flyaway$epoch = Long.MIN_VALUE;
    @Unique private static final Item[] FLYAWAY$ITEMS = new Item[FLYAWAY$N];
    @Unique private static final ItemStack[] FLYAWAY$RESULTS = new ItemStack[FLYAWAY$N];
    @Unique private static int flyaway$size;
    @Unique private static int flyaway$next;

    @Inject(method = "findEquippedCurio", at = @At("HEAD"), cancellable = true)
    private static void flyaway$memoHit(Entity entity, Item item, CallbackInfoReturnable<ItemStack> cir) {
        if (!(entity instanceof Player) || !FrameStamp.onClientThread()) return; // non-players: original is trivial
        WeakReference<Entity> ref = flyaway$entity;
        if (ref == null || ref.get() != entity || flyaway$epoch != FrameStamp.epoch) return;
        for (int i = 0; i < flyaway$size; i++) {
            if (FLYAWAY$ITEMS[i] == item) {
                ItemStack stack = FLYAWAY$RESULTS[i];
                if (stack.isEmpty() ? stack == ItemStack.EMPTY : stack.getItem() == item) {
                    cir.setReturnValue(stack);
                }
                return;
            }
        }
    }

    @Inject(method = "findEquippedCurio", at = @At("RETURN"))
    private static void flyaway$memoStore(Entity entity, Item item, CallbackInfoReturnable<ItemStack> cir) {
        if (!(entity instanceof Player) || !FrameStamp.onClientThread()) return; // non-players: original is trivial
        WeakReference<Entity> ref = flyaway$entity;
        if (ref == null || ref.get() != entity || flyaway$epoch != FrameStamp.epoch) {
            if (ref == null || ref.get() != entity) flyaway$entity = new WeakReference<>(entity);
            flyaway$epoch = FrameStamp.epoch;
            java.util.Arrays.fill(FLYAWAY$ITEMS, null);
            java.util.Arrays.fill(FLYAWAY$RESULTS, null);
            flyaway$size = 0;
            flyaway$next = 0;
        }
        ItemStack result = cir.getReturnValue();
        for (int i = 0; i < flyaway$size; i++) {
            if (FLYAWAY$ITEMS[i] == item) {
                FLYAWAY$RESULTS[i] = result;
                return;
            }
        }
        int slot;
        if (flyaway$size < FLYAWAY$N) {
            slot = flyaway$size++;
        } else {
            slot = flyaway$next;
            flyaway$next = (flyaway$next + 1) % FLYAWAY$N;
        }
        FLYAWAY$ITEMS[slot] = item;
        FLYAWAY$RESULTS[slot] = result;
    }
}
