package dev.flyaway.perf.mixin.compat.simplyswords;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.compat.SimplySwordsBeeCount;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.world.level.entity.EntityLookup;
import net.sweenus.simplyswords.entity.SimplySwordsBeeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * What: keep a count of {@code SimplySwordsBeeEntity} instances in each {@code EntityLookup} (the visible-entity
 * storage behind {@code ServerLevel.getAllEntities()}), for {@link HivemindSwarmHasActiveMixin}.
 *
 * <p>The count follows the {@code byId} map exactly: it is adjusted from the values actually put into / removed from
 * that map (the {@code put} return value covers a replaced entry, the {@code remove} return value covers a missed
 * removal), so it always equals the number of bee values that {@code getAllEntities()} would iterate.
 * Cost: one {@code instanceof} per entity add/remove (entity join/leave, chunk load/unload), nothing per tick.
 *
 * <p>Disable: {@code mixin.compat.EntityLookupBeeCountMixin=false} (also disables the hasActive shortcut, which then
 * falls back to the original scan).
 */
@Mixin(EntityLookup.class)
public abstract class EntityLookupBeeCountMixin implements SimplySwordsBeeCount {
    @Unique private int flyaway$bees;

    @Override
    public int flyaway$beeCount() {
        return this.flyaway$bees;
    }

    @WrapOperation(method = "add(Lnet/minecraft/world/level/entity/EntityAccess;)V",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;put(ILjava/lang/Object;)Ljava/lang/Object;"))
    private Object flyaway$countAdd(Int2ObjectMap<Object> map, int id, Object value, Operation<Object> original) {
        Object prev = original.call(map, id, value);
        if (prev != value) {
            if (value instanceof SimplySwordsBeeEntity) this.flyaway$bees++;
            if (prev instanceof SimplySwordsBeeEntity) this.flyaway$bees--;
        }
        return prev;
    }

    @WrapOperation(method = "remove(Lnet/minecraft/world/level/entity/EntityAccess;)V",
            at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;remove(I)Ljava/lang/Object;"))
    private Object flyaway$countRemove(Int2ObjectMap<Object> map, int id, Operation<Object> original) {
        Object removed = original.call(map, id);
        if (removed instanceof SimplySwordsBeeEntity) this.flyaway$bees--;
        return removed;
    }
}
