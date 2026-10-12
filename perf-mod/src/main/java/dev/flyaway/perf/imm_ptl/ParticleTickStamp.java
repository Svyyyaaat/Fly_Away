package dev.flyaway.perf.imm_ptl;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;

/** Which client levels had their particles ticked recently (render thread only). */
public final class ParticleTickStamp {
    private static final Map<ClientLevel, Long> LAST = new WeakHashMap<>();

    private ParticleTickStamp() {}

    public static void mark(ClientLevel level) {
        if (level != null) LAST.put(level, System.nanoTime());
    }

    public static boolean ticking(ClientLevel level) {
        Long t = LAST.get(level);
        return t != null && System.nanoTime() - t < 250_000_000L;
    }
}
