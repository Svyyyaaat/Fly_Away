package dev.flyaway.perf.compat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import toni.chunkactivitytracker.ChunkActivityTracker;
import toni.chunkactivitytracker.data.ChunkActivityMap;

/**
 * Off-thread writer for Chunk Activity Tracker's per-dimension data files.
 *
 * <p>The mod rewrites {@code <world>/chunk_activity_info/<dim>.dat} - one entry for every chunk a player ever stood in
 * (grows without bound) - with GZIP on the server thread at the end of every {@code MinecraftServer.saveEverything}
 * (5-minute autosave, every singleplayer pause, /save-all) and after the first load of each dimension.
 *
 * <p>Here the map is still serialised on the calling (server) thread with the mod's own {@code CODEC} - a consistent
 * snapshot, identical bytes - but compression and the file write run on one background thread. Writes go to a
 * temp file that is atomically moved over the target, so a reader never sees a half-written file, and they are
 * strictly ordered (single thread). If a newer snapshot for the same file arrives before the queued one started,
 * only the newest is written. {@link #drain()} waits for all pending writes; it is called before the mod loads a file
 * and when it clears its maps on server stop, so no data is lost on quit and a re-opened world reads the latest file.
 */
public final class ChunkActivitySaveQueue {
    private record Pending(byte[] raw, int chunks, String dimension) {}

    private static final ConcurrentHashMap<Path, Pending> PENDING = new ConcurrentHashMap<>();
    private static volatile ExecutorService executor;

    private ChunkActivitySaveQueue() {}

    private static ExecutorService executor() {
        ExecutorService e = executor;
        if (e == null) {
            synchronized (ChunkActivitySaveQueue.class) {
                e = executor;
                if (e == null) {
                    executor = e = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "FlyAway ChunkActivity IO");
                        t.setDaemon(true);
                        t.setPriority(Thread.NORM_PRIORITY - 1);
                        return t;
                    });
                }
            }
        }
        return e;
    }

    /**
     * Snapshot {@code map} now and write it asynchronously. Returns false if the caller should run the original
     * synchronous save instead (no world path / encoding failed - the original then logs its usual error).
     */
    public static boolean submit(ChunkActivityMap map) {
        Path file;
        try {
            file = ChunkActivityMap.datafile(map.getDimension());
        } catch (Throwable t) {
            return false;
        }
        if (file == null) return false;

        byte[] raw;
        ByteBuf buffer = Unpooled.buffer();
        try {
            ChunkActivityMap.CODEC.encode(buffer, map);
            raw = new byte[buffer.readableBytes()];
            buffer.readBytes(raw);
        } catch (Throwable t) {
            return false;
        } finally {
            buffer.release();
        }

        if (PENDING.put(file, new Pending(raw, map.getChunks().size(), map.getDimension())) == null) {
            executor().execute(() -> write(file));
        }
        return true;
    }

    private static void write(Path file) {
        Pending p = PENDING.remove(file);
        if (p == null) return;
        long start = System.currentTimeMillis();
        Path tmp = file.resolveSibling(file.getFileName() + ".flyaway-tmp");
        try {
            try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(tmp))) {
                out.write(p.raw());
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            ChunkActivityTracker.LOGGER.info("Saving data for " + p.chunks() + " chunks for dimension '" + p.dimension()
                    + "' took " + (System.currentTimeMillis() - start) + " milliseconds (off-thread, flyaway_perf)");
        } catch (Exception e) {
            ChunkActivityTracker.LOGGER.error("Error when saving chunk activity info: " + e.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
    }

    /** Block until every queued write has finished (bounded wait). */
    public static void drain() {
        ExecutorService e = executor;
        if (e == null) return;
        try {
            e.submit(() -> {}).get(60, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
    }
}
