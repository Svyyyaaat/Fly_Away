package dev.flyaway.perf.mixin.compat.simplyswords;

import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The visible-entity lookup whose values {@code LevelEntityGetterAdapter.getAll()} returns. */
@Mixin(LevelEntityGetterAdapter.class)
public interface LevelEntityGetterAdapterAccessor {
    @Accessor("visibleEntities")
    EntityLookup<?> flyaway$visibleEntities();
}
