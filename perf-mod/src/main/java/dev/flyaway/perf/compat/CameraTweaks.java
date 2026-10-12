package dev.flyaway.perf.compat;

import dev.flyaway.perf.PerfConfig;
import net.neoforged.fml.ModList;

/**
 * Switches for the third-person camera fixes (see docs/camera.md). Deliberately free of Immersive Portals / Shoulder
 * Surfing class references so mixins can test these flags without loading optional mods' classes.
 */
public final class CameraTweaks {
    private CameraTweaks() {}

    /** camera.portalsAreWalls: third-person cameras (vanilla F5, Shoulder Surfing, Epic Fight TPS) stop at portal planes. */
    public static final boolean PORTALS_ARE_WALLS = PerfConfig.getBool("camera.portalsAreWalls", true);

    /** camera.taczRecoilInShoulderSurfing: TACZ camera recoil is applied straight to the Shoulder Surfing camera. */
    public static final boolean TACZ_RECOIL_IN_SHOULDER_SURFING = PerfConfig.getBool("camera.taczRecoilInShoulderSurfing", true);

    /** Immersive Portals present (mixins outside the imm_ptl area must not touch its classes otherwise). */
    public static final boolean IMM_PTL = isLoaded("immersive_portals_core");

    private static boolean isLoaded(String id) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(id);
    }
}
