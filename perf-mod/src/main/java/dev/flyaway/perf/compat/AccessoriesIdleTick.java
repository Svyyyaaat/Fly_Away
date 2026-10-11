package dev.flyaway.perf.compat;

import com.google.common.collect.ImmutableMap;
import dev.flyaway.perf.mixin.compat.accessories.AccessoriesHolderImplAccessor;
import io.wispforest.accessories.AccessoriesInternals;
import io.wispforest.accessories.api.AccessoriesCapability;
import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.api.AccessoriesHolder;
import io.wispforest.accessories.api.events.ContainersChangeCallback;
import io.wispforest.accessories.data.EntitySlotLoader;
import io.wispforest.accessories.impl.AccessoriesHolderImpl;
import io.wispforest.accessories.impl.ExpandedSimpleContainer;
import java.util.Map;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Idle fast path for {@code io.wispforest.accessories.impl.AccessoriesEventHandler.onLivingEntityTick} (Accessories
 * 1.1.0-beta.48, nested in the Aether jar). See {@code AccessoriesEntityTickIdleMixin}.
 *
 * <p>The original method, for an entity with accessory slots, does (server side):
 * <ol>
 *   <li>{@code capability = AccessoriesCapability.get(entity)}: slot map lookup, lazily creates the capability object,
 *       {@code getHolder()} (which re-initialises the holder only if it was just loaded from NBT or the slot count
 *       changed);</li>
 *   <li>for every container and slot: ticks non-empty stacks; if the stack differs from the remembered previous
 *       stack (or the cosmetic one does) it records the change, fires equip/unequip hooks and criteria;</li>
 *   <li>applies the collected attribute changes (no-op for empty builders), {@code updateContainers()} (no-op when no
 *       container is flagged), posts {@code ContainersChangeCallback} with a copy of the pending-update map, sends a
 *       sync packet if anything was dirty, clears the pending map;</li>
 *   <li>re-creates the capability if the holder is still "loaded from tag", drops invalid stacks.</li>
 * </ol>
 * On the client it stops after step 2 (and step 2 only ticks non-empty stacks).
 *
 * <p>When all accessory stacks are empty, every stack (and cosmetic stack) still {@code ItemStack.matches} its remembered
 * previous stack (server only; this is the original's own change test), no container is flagged,
 * the pending-update map and the invalid-stack list are empty and the holder is initialised, steps 2-4 change nothing,
 * so only step 1 and the callback post (with the same empty {@code ImmutableMap}) are performed here. All checks are
 * side-effect free reads (getAccessories()/getCosmeticAccessories() only run {@code update()}, which does nothing
 * when the container is not flagged, and the flag is checked first).
 */
public final class AccessoriesIdleTick {
    private AccessoriesIdleTick() {}

    /** @return true if the tick was fully handled here (the original must be skipped). */
    public static boolean tryIdleTick(LivingEntity entity) {
        if (entity.isRemoved()) return false; // original does nothing; keep it on its own path
        Map<?, ?> slots = EntitySlotLoader.getEntitySlots(entity);
        if (slots.isEmpty()) return false; // no capability: original path is already cheap

        AccessoriesHolder rawHolder = AccessoriesInternals.getHolder(entity); // same attachment the original reads
        if (!(rawHolder instanceof AccessoriesHolderImpl holder)) return false;
        AccessoriesHolderImplAccessor acc = (AccessoriesHolderImplAccessor) holder;
        // getHolder() must be a pure lookup (no reset/init) - exactly its own condition, negated
        if (acc.flyaway$loadedFromTag()) return false;
        if (holder.getSlotContainers().size() != slots.size()) return false;

        boolean client = entity.level().isClientSide();
        if (!client) {
            if (!holder.invalidStacks.isEmpty()) return false;
            if (!acc.flyaway$containersRequiringUpdates().isEmpty()) return false;
        }

        for (AccessoriesContainer container : acc.flyaway$getAllSlotContainers().values()) {
            if (container.hasChanged()) return false;
            // updateContainers() -> getContainers() would re-bind containers owned by another entity
            if (container.capability() == null || container.capability().entity() != entity) return false;
            ExpandedSimpleContainer stacks = container.getAccessories();
            ExpandedSimpleContainer cosmetics = container.getCosmeticAccessories();
            int size = stacks.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack current = stacks.getItem(i);
                if (!current.isEmpty()) return false; // would be ticked
                if (!client) {
                    // exactly the original's change tests (an "empty" stack with count 0 / another item does NOT match
                    // ItemStack.EMPTY, so isEmpty() alone would not be enough)
                    if (!ItemStack.matches(current, stacks.getPreviousItem(i))) return false;
                    if (!ItemStack.matches(cosmetics.getItem(i), cosmetics.getPreviousItem(i))) return false;
                }
            }
        }

        // Step 1 of the original (its getHolder() is a pure lookup now, see the checks above).
        AccessoriesCapability capability = AccessoriesCapability.get(entity);
        if (capability == null) return false; // cannot happen (slots non-empty); be safe
        if (!client) {
            // ImmutableMap.copyOf(emptyMap) returns this same singleton.
            ContainersChangeCallback.EVENT.invoker().onChange(entity, capability, ImmutableMap.of());
        }
        return true;
    }
}
