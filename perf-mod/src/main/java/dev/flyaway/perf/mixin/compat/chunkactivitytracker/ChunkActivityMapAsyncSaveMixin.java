package dev.flyaway.perf.mixin.compat.chunkactivitytracker;

import dev.flyaway.perf.compat.ChunkActivitySaveQueue;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import toni.chunkactivitytracker.data.ChunkActivityMap;

/**
 * Chunk Activity Tracker: move the GZIP + disk write of its data files off the server thread.
 *
 * <p>Problem: {@code ChunkActivityMap.save()} encodes and GZIP-writes the WHOLE per-dimension map (one entry per chunk
 * any player ever stood in; never pruned) synchronously on the server thread for every loaded dimension at the end of
 * each {@code MinecraftServer.saveEverything} - i.e. on every 5-minute autosave, every time the singleplayer game is
 * paused, and on /save-all - plus once after loading each dimension's map. On a long-played, much-flown world this is
 * megabytes of deflate + a file write (with the user's nearly full swap / busy disk) inside a tick: a periodic
 * server-tick spike that grows with play time. No other mod in the pack reads this data.
 *
 * <p>Fix: serialise on the calling thread (consistent snapshot, the mod's own CODEC, same bytes), compress + write on
 * a background thread via {@link ChunkActivitySaveQueue} (ordered, coalesced, temp file + atomic move). Pending writes
 * are drained before the mod loads a file and when it clears its maps on server stop, so nothing is lost on quit.
 *
 * <p>Expected gain: removes the whole compression/IO part of the save from the tick (tens to hundreds of ms per
 * autosave/pause on old worlds; only the in-memory encode remains). Gameplay: none (the data itself is unchanged).
 * Disable: {@code mixin.compat.ChunkActivityMapAsyncSaveMixin=false}.
 */
@Mixin(value = ChunkActivityMap.class, remap = false)
public abstract class ChunkActivityMapAsyncSaveMixin {
    @Inject(method = "save()V", at = @At("HEAD"), cancellable = true)
    private void flyaway$saveOffThread(CallbackInfo ci) {
        if (ChunkActivitySaveQueue.submit((ChunkActivityMap) (Object) this)) ci.cancel();
    }

    @Inject(method = "load(Lnet/minecraft/resources/ResourceKey;)Ltoni/chunkactivitytracker/data/ChunkActivityMap;", at = @At("HEAD"))
    private static void flyaway$drainBeforeLoad(ResourceKey<Level> dimension, CallbackInfoReturnable<ChunkActivityMap> cir) {
        ChunkActivitySaveQueue.drain();
    }

    @Inject(method = "clear()V", at = @At("HEAD"))
    private static void flyaway$drainOnStop(CallbackInfo ci) {
        ChunkActivitySaveQueue.drain();
    }
}
