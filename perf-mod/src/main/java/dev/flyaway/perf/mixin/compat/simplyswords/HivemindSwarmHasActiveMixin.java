package dev.flyaway.perf.mixin.compat.simplyswords;

import dev.flyaway.perf.compat.SimplySwordsBeeCount;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.LevelEntityGetter;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;
import net.sweenus.simplyswords.world.HivemindSwarmManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * What: {@code HivemindSwarmManager.hasActive(level)} returns false immediately when the level holds no
 * {@code SimplySwordsBeeEntity} at all.
 *
 * <p>Why: Simply Swords injects {@code tickFieldManagers} at the HEAD of every {@code ServerLevel.tick}; of its ~35
 * {@code hasActive} checks, the Hivemind one is the only one that iterates {@code level.getAllEntities()} (every
 * entity of the dimension, every tick, every dimension) looking for a swarm bee. Profile: 0.34% of the server thread
 * (0.89% for the whole tickFieldManagers), and it grows linearly with the number of loaded entities.
 *
 * <p>Fix: {@code ServerLevel.getAllEntities()} is {@code getEntities().getAll()}, which for the vanilla
 * {@code LevelEntityGetterAdapter} iterates its {@code EntityLookup}'s {@code byId} values. If that lookup's exact bee
 * count ({@link EntityLookupBeeCountMixin}) is 0, the original loop cannot find a bee and returns false. In every
 * other case (bees present, a non-vanilla getter, the counter mixin disabled) the original scan runs.
 *
 * <p>Disable: {@code mixin.compat.HivemindSwarmHasActiveMixin=false}
 */
@Mixin(value = HivemindSwarmManager.class, remap = false)
public abstract class HivemindSwarmHasActiveMixin {
    @Inject(method = "hasActive(Lnet/minecraft/server/level/ServerLevel;)Z", at = @At("HEAD"), cancellable = true)
    private static void flyaway$noBees(ServerLevel world, CallbackInfoReturnable<Boolean> cir) {
        LevelEntityGetter<Entity> getter = world.getEntities();
        if (getter instanceof LevelEntityGetterAdapter<?> adapter
                && ((LevelEntityGetterAdapterAccessor) adapter).flyaway$visibleEntities() instanceof SimplySwordsBeeCount count
                && count.flyaway$beeCount() == 0) {
            cir.setReturnValue(false);
        }
    }
}
