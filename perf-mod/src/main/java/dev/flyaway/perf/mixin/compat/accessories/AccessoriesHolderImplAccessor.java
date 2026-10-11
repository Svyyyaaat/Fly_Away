package dev.flyaway.perf.mixin.compat.accessories;

import io.wispforest.accessories.api.AccessoriesContainer;
import io.wispforest.accessories.impl.AccessoriesHolderImpl;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Read-only access to the protected state of Accessories' per-entity holder, used by
 * {@link dev.flyaway.perf.compat.AccessoriesIdleTick} to decide (without side effects) whether an entity's
 * accessory tick would be a no-op.
 */
@Mixin(value = AccessoriesHolderImpl.class, remap = false)
public interface AccessoriesHolderImplAccessor {
    @Accessor("loadedFromTag")
    boolean flyaway$loadedFromTag();

    @Accessor("containersRequiringUpdates")
    Map<AccessoriesContainer, Boolean> flyaway$containersRequiringUpdates();

    @Invoker("getAllSlotContainers")
    Map<String, AccessoriesContainer> flyaway$getAllSlotContainers();
}
