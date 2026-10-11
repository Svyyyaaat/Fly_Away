package dev.flyaway.perf.mixin.compat.curios;

import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import top.theillusivec4.curios.common.inventory.CurioStacksHandler;

/** Read access to the private cached-modifier map (see {@link CurioInventoryClearCachedIdleMixin}). */
@Mixin(value = CurioStacksHandler.class, remap = false)
public interface CurioStacksHandlerCachedModsAccessor {
    @Accessor("cachedModifiers")
    Map<ResourceLocation, AttributeModifier> flyaway$cachedModifiers();
}
