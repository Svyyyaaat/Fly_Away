package dev.flyaway.perf.imm_ptl.postcard;

import static org.lwjgl.opengl.GL33C.*;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.flyaway.perf.FlyAwayPerf;
import dev.flyaway.perf.imm_ptl.ImmPtlTunables;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

/**
 * Postcards by portal UUID. Must not reference Immersive Portals or Iris classes: the eviction hooks
 * ({@link #requestClear()}, {@link #onLevelUnload}) are registered from {@code FlyAwayPerf} even when neither mod is
 * installed. Render thread only, except {@link #requestClear()} which may come from anywhere.
 * <p>
 * Eviction: LRU when the memory cap is reached; unused for {@value #IDLE_EVICT_S} s; portal entity removed and not
 * seen again for {@value #REMOVED_EVICT_S} s; the client level the portal lives in unloads; resource reload; Iris
 * shader pack reload (checked by the caller via {@link #checkGeneration(int)}).
 */
public final class PostcardCache {
    private static final long IDLE_EVICT_S = 120;
    private static final long REMOVED_EVICT_S = 10;

    /** One postcard: GL textures plus the camera it was captured with. */
    public static final class Entry {
        final UUID id;
        ResourceKey<Level> srcDim;   // level the portal lives in
        ResourceKey<Level> destDim;  // where it leads (part of the key)
        WeakReference<Entity> portal = new WeakReference<>(null);

        // GL objects (0 = not allocated)
        int colorTex, depthTex, fbo, res;

        // capture data (valid when captured == true)
        boolean captured;
        final Matrix4f capVP = new Matrix4f();
        double camX, camY, camZ;
        float projA, projB;             // capture projection m22, m32
        float rx0, ry0, rx1, ry1;       // captured NDC rect
        int usedW, usedH;
        double destX, destY, destZ;     // portal destination at capture time
        int capturedFrame = Integer.MIN_VALUE / 2;
        long capturedNanos;
        float score;                    // capture quality: 1 = complete direct view; main-view captures score lower
        boolean direct;                 // captured in a pass through this portal (vs. from the player's own view)

        // display / fade state
        int lastMainTryFrame = Integer.MIN_VALUE / 2;
        int lastNestedFrame = Integer.MIN_VALUE / 2;
        int lastLiveFrame = Integer.MIN_VALUE / 2;
        long fadeStartNanos;            // postcard -> live fade, 0 = none
        long firstShownNanos;           // swirl -> postcard fade-in, 0 = not shown yet
        long lastUsedNanos;
        long removedSinceNanos;

        // fallback tint
        float tintR = 0.45f, tintG = 0.25f, tintB = 0.7f;
        int tintFrame = Integer.MIN_VALUE / 2;

        Entry(UUID id) {
            this.id = id;
        }

        boolean hasGl() {
            return fbo != 0;
        }

        void releaseGl() {
            if (fbo != 0) glDeleteFramebuffers(fbo);
            if (colorTex != 0) glDeleteTextures(colorTex);
            if (depthTex != 0) glDeleteTextures(depthTex);
            fbo = colorTex = depthTex = 0;
            captured = false;
        }
    }

    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();
    private static volatile boolean clearRequested;
    private static int generation = Integer.MIN_VALUE;
    private static long nextSweepNanos;

    private PostcardCache() {}

    static Entry get(UUID id) {
        return ENTRIES.get(id);
    }

    static Entry getOrCreate(UUID id) {
        Entry e = ENTRIES.get(id);
        if (e == null) {
            e = new Entry(id);
            ENTRIES.put(id, e);
        }
        return e;
    }

    static long bytesPerEntry() {
        long r = ImmPtlTunables.POSTCARD_RESOLUTION;
        return r * r * 8L * 4 / 3; // RGBA8 + RG16F, with mip chains
    }

    /** Max number of entries with GL storage, from the memory cap. */
    static int maxGlEntries() {
        long cap = ImmPtlTunables.POSTCARD_MAX_MEMORY_MB * 1024L * 1024L;
        return (int) Math.max(1, cap / bytesPerEntry());
    }

    /** True if one more entry can get GL storage without evicting another. */
    static boolean hasFreeGlSlot() {
        int withGl = 0;
        for (Entry e : ENTRIES.values()) if (e.hasGl()) withGl++;
        return withGl < maxGlEntries();
    }

    /** Frees the least recently used entry with GL storage, other than {@code keep}, if the cap is reached. */
    static void makeRoomFor(Entry keep) {
        int withGl = 0;
        Entry lru = null;
        for (Entry e : ENTRIES.values()) {
            if (!e.hasGl()) continue;
            withGl++;
            if (e != keep && (lru == null || e.lastUsedNanos < lru.lastUsedNanos)) lru = e;
        }
        if (withGl >= maxGlEntries() && lru != null) {
            if (ImmPtlTunables.POSTCARD_DEBUG) FlyAwayPerf.LOGGER.info("[postcards] evict LRU {}", lru.id);
            lru.releaseGl();
        }
    }

    /** Clears everything when a reload was requested or the Iris pipeline generation changed. Render thread. */
    static void checkGeneration(int irisGeneration) {
        if (clearRequested || irisGeneration != generation) {
            if (ImmPtlTunables.POSTCARD_DEBUG && !ENTRIES.isEmpty()) {
                FlyAwayPerf.LOGGER.info("[postcards] clearing {} postcards (reload)", ENTRIES.size());
            }
            clearRequested = false;
            generation = irisGeneration;
            clearNow();
        }
    }

    /** Periodic eviction of idle and removed portals. Render thread, called once per portal pass at most. */
    static void sweep(long now) {
        if (now < nextSweepNanos) return;
        nextSweepNanos = now + 2_000_000_000L;
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            Entity p = e.portal.get();
            boolean removed = p == null || p.isRemoved();
            if (removed) {
                if (e.removedSinceNanos == 0) e.removedSinceNanos = now;
            } else {
                e.removedSinceNanos = 0;
            }
            boolean idle = now - e.lastUsedNanos > IDLE_EVICT_S * 1_000_000_000L;
            boolean gone = removed && now - e.removedSinceNanos > REMOVED_EVICT_S * 1_000_000_000L;
            if (idle || gone) {
                e.releaseGl();
                it.remove();
            }
        }
    }

    private static void clearNow() {
        for (Entry e : ENTRIES.values()) e.releaseGl();
        ENTRIES.clear();
    }

    /** Resource reload, logout: clear on the next render-thread use (or now if already on it). */
    public static void requestClear() {
        if (RenderSystem.isOnRenderThread()) {
            clearRequested = false;
            clearNow();
        } else {
            clearRequested = true;
        }
    }

    /** A client level unloaded: drop the postcards of portals that lived in it. */
    public static void onLevelUnload(ResourceKey<Level> dim) {
        if (!RenderSystem.isOnRenderThread()) {
            clearRequested = true;
            return;
        }
        Iterator<Entry> it = ENTRIES.values().iterator();
        while (it.hasNext()) {
            Entry e = it.next();
            if (e.srcDim == dim) {
                e.releaseGl();
                it.remove();
            }
        }
    }

    static int size() {
        return ENTRIES.size();
    }
}
