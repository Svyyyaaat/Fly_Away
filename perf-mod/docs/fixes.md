# Bug fixes (non-perf)

## Hotbar slot jumps when going through an Immersive Portals portal

Symptom (singleplayer): entering a cross-dimension IP portal switches the selected hotbar slot to some other slot;
always the same slot within a session, a different one in other sessions.

### Root cause: Carry On's join-world sync sends a stale slot, which IP's teleport never corrects

1. `tschipp.carryon.events.CommonEvents.onJoinWorld(EntityJoinLevelEvent)` (Carry On 2.2.6.13): for every
   `ServerPlayer` joining a level it sends `new ClientboundSyncCarryDataPacket(sp.getId(), CarryOnDataManager.getCarryData(sp))`
   to that player, using the stored data as-is.
2. `ClientboundSyncCarryDataPacket.handle` on the client runs unconditionally (carrying or not):
   `p.getInventory().selected = this.data.getSelected();`
3. `CarryOnData.selectedSlot` (persisted in the `carryon:carry_on_data` attachment as `"selected"`) is only updated
   by `CarryOnDataManager.setCarryData` (`data.setSelected(player.getInventory().selected)`), which runs at login
   (`PlayerMixin` at RETURN of `Player.readAdditionalSaveData`, i.e. the slot you logged in with), on carry-key/sneak
   press and release (`ServerboundCarryKeyPressedPacket`, carry key is unbound on NeoForge so it falls back to
   sneak), and on pickup/place. So it holds "the slot at login or at the last sneak": a per-session stale value,
   matching the report.
4. Vanilla hides this: `ServerPlayer.changeDimension` does `serverlevel.addDuringTeleport(this)` (NeoForge fires
   `EntityJoinLevelEvent` in `ServerLevel.addPlayer`, Carry On sends the stale slot) and then
   `playerlist.sendAllPlayerInfo(this)`, which sends `ClientboundSetCarriedItemPacket(inventory.selected)` and puts
   the right slot back. Respawn does the same.
5. IP 6.0.7 `ServerTeleportationManager.changePlayerDimension` does `fromWorld.removePlayerImmediately(...)`,
   `player.setServerLevel(toWorld)`, `toWorld.addDuringTeleport(player)`, so the join event fires and Carry On sends
   the stale slot, but it never calls `sendAllPlayerInfo`. The client is already in the destination level
   (client-side IP teleports first), so `player.level().getEntity(id)` finds the local player and the slot changes.
   `MultiPlayerGameMode.ensureHasSentCarriedItem` then reports the wrong slot back to the server, so it sticks on
   both sides. micimmptl's `PlayerDimensionFunnelMixin` only adds the NeoForge travel/changed-dimension events and
   does not touch the inventory.

What we ruled out (bytecode scan of every jar, nested jars included):
- Nothing else sends `ClientboundSetCarriedItemPacket` or calls `PlayerList.sendAllPlayerInfo`. The only mod sender of
  `ServerboundSetCarriedItemPacket` is Collective's fake-player handler.
- Mods that write `Inventory.selected` (PUTFIELD): only Carry On (`ClientboundSyncCarryDataPacket`,
  `CarryOnCommon.onCarryTick`, `CarryOnCommonClient.onCarryClientTick`, `PlacementHandler.placeCarriedOnDeath`) and
  Fabric API's pick-block mixin. The tick writes only run while carrying.
- `PlayerChangedDimensionEvent` listeners (Epic Fight, Iron's, Aether, Relics, ...) don't touch the slot. Epic Fight
  only resets animations and re-sends skill data.
- IP's client `ClientTeleportationManager.changePlayerDimension` moves the same `LocalPlayer` between levels. It
  doesn't recreate the player or touch the inventory.

### Fix

`dev.flyaway.perf.mixin.compat.carryon.CarryOnJoinSyncSelectedSlotMixin` (common side, `flyaway_perf.compat.mixins.json`
"mixins") puts a `@WrapOperation` on the `CarryOnDataManager.getCarryData` call inside `CommonEvents.onJoinWorld`.
When the player is **not** carrying anything, it sets `data.selected` to the player's current slot before the packet
is built, which is what Carry On's own `setCarryData` does. While the player is carrying, it changes nothing: Carry
On's `onCarryTick` already forces `inventory.selected = data.selected` every tick, so the two values match and the
carry slot lock keeps working.

- Risk: very low. It only refreshes a field that Carry On itself refreshes from the same source, and only on the
  join-world sync. Login is unaffected: the stored value already equals the login slot there.
- Disable: `fix.keepHotbarSlotOnPortal=false`, or `mixin.compat.CarryOnJoinSyncSelectedSlotMixin=false`.

### How to verify
In the dev/harness client: create a world and select slot 1. Sneak once so Carry On stores slot 1. Select slot 6,
then use an IP nether portal, or run `/execute in minecraft:the_nether run tp @s ~ ~ ~`. Vanilla tp goes through
`changeDimension` and is not affected, so the IP portal path is the real test. Without the fix the slot becomes 1.
With the fix it stays 6. Check that the slot is the same on the server: `/data get entity @s SelectedItemSlot` should
print 5. Also check that the Carry On lock still works: pick up a chest (sneak and right-click with an empty hand),
go through a portal, and the slot should stay locked to the carry slot.
