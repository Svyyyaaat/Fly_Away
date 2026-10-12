package dev.flyaway.perf.mixin.compat.carryon;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.flyaway.perf.PerfConfig;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import tschipp.carryon.common.carry.CarryOnData;
import tschipp.carryon.events.CommonEvents;

/**
 * BUG FIX (not perf): the selected hotbar slot jumps to a stale slot when the player goes through an Immersive
 * Portals portal into another dimension.
 *
 * <p>Root cause: Carry On's {@code CommonEvents.onJoinWorld} ({@code EntityJoinLevelEvent}, ServerPlayer) sends the
 * player its stored {@code CarryOnData} as-is, and the client handler {@code ClientboundSyncCarryDataPacket.handle}
 * unconditionally does {@code player.getInventory().selected = data.getSelected()}. {@code CarryOnData.selectedSlot}
 * is only refreshed when Carry On calls {@code setCarryData} (on login in {@code Player.readAdditionalSaveData}, on
 * carry-key/sneak press and release, on pickup/place), so it holds an old slot. Vanilla dimension change hides this:
 * {@code ServerPlayer.changeDimension} calls {@code PlayerList.sendAllPlayerInfo} (which sends
 * {@code ClientboundSetCarriedItemPacket(selected)}) right AFTER {@code addDuringTeleport} fired the join event. IP's
 * {@code ServerTeleportationManager.changePlayerDimension} also re-adds the player through
 * {@code ServerLevel.addDuringTeleport} (join event fires -> stale slot sent) but never calls {@code sendAllPlayerInfo},
 * so the stale slot sticks and the client then reports it back to the server (ensureHasSentCarriedItem).
 *
 * <p>Fix: when the join-sync reads the data and the player is NOT carrying, refresh {@code selected} to the player's
 * current slot first (exactly what Carry On's own {@code setCarryData} does). While carrying, Carry On's
 * {@code onCarryTick} already forces {@code inventory.selected = data.selected} every tick, so both are equal and the
 * data is left untouched (carry slot lock keeps working).
 *
 * <p>Disable: {@code fix.keepHotbarSlotOnPortal=false} or {@code mixin.compat.CarryOnJoinSyncSelectedSlotMixin=false}
 */
@Mixin(value = CommonEvents.class, remap = false)
public abstract class CarryOnJoinSyncSelectedSlotMixin {
    @Unique
    private static final boolean FLYAWAY$ENABLED = PerfConfig.getBool("fix.keepHotbarSlotOnPortal", true);

    @WrapOperation(
            method = "onJoinWorld(Lnet/neoforged/neoforge/event/entity/EntityJoinLevelEvent;)V",
            at = @At(value = "INVOKE",
                    target = "Ltschipp/carryon/common/carry/CarryOnDataManager;getCarryData(Lnet/minecraft/world/entity/player/Player;)Ltschipp/carryon/common/carry/CarryOnData;"))
    private static CarryOnData flyaway$refreshSelected(Player player, Operation<CarryOnData> original) {
        CarryOnData data = original.call(player);
        if (FLYAWAY$ENABLED && data != null && !data.isCarrying()) {
            int slot = player.getInventory().selected;
            if (data.getSelected() != slot) data.setSelected(slot);
        }
        return data;
    }
}
