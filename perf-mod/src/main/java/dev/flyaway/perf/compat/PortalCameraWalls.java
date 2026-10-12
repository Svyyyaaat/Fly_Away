package dev.flyaway.perf.compat;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.my_util.RayTraceResult;

/**
 * "Portals are walls" for third-person cameras. Every third-person camera in the pack (vanilla Camera.getMaxZoom,
 * Shoulder Surfing's ShoulderSurfingCamera.maxZoom, Epic Fight's TPS camera) shortens the camera distance by casting
 * 8 slightly offset rays from the eye towards the wanted camera position and taking the nearest block hit. We wrap
 * each of those block clips: if the same ray crosses an Immersive Portals portal (inside its bounds, entering from the
 * portal's front side) before the block hit, the portal hit is returned as if it were a block face. The callers then
 * clamp exactly as they do for blocks, so the camera keeps the same ~0.1 block margin in front of the portal plane.
 *
 * <p>Only portals whose front side faces the player's eye count: when the eye itself is within the 0.1 corner offset of
 * a plane (mid-walk through a portal) the twin portal on the other side is not treated as a wall.
 * Render thread only (camera setup); the tiny cache below is a single immutable snapshot, so a stray call from another
 * thread is still safe.
 */
public final class PortalCameraWalls {
    private PortalCameraWalls() {}

    private static final double LENIENCY = 0.001; // same as IP's own PortalUtils.raytracePortals
    private static final long CACHE_NANOS = 20_000_000L; // portal list reused by the 8 rays of one camera setup

    private record Snapshot(Level level, double x, double y, double z, double range, long time, List<Portal> portals) {}

    private static volatile Snapshot cache;

    private static List<Portal> portalsNear(Level level, Vec3 eye, double range) {
        long now = System.nanoTime();
        Snapshot s = cache;
        if (s != null && s.level == level && s.x == eye.x && s.y == eye.y && s.z == eye.z && range <= s.range
                && now - s.time < CACHE_NANOS) {
            return s.portals;
        }
        // Same lookup IP itself uses for portal ray casts (chunk-rough, so big portals whose centre is in a
        // neighbouring chunk are found too), plus global portals.
        List<Portal> found = McHelper.getEntitiesNearby(level, eye, Portal.class, range);
        List<Portal> global = GlobalPortalStorage.getGlobalPortals(level);
        List<Portal> all;
        if (global == null || global.isEmpty()) {
            all = found;
        } else {
            all = new ArrayList<>(found.size() + global.size());
            all.addAll(found);
            all.addAll(global);
        }
        cache = new Snapshot(level, eye.x, eye.y, eye.z, range, now, all);
        return all;
    }

    /**
     * @param eye      the player's eye (ray origin before the corner offset)
     * @param from     ray start, as passed to the block clip
     * @param to       ray end, as passed to the block clip
     * @param blockHit the block clip result
     * @return blockHit, or a BLOCK-type hit at the portal plane when a portal is crossed before it
     */
    public static BlockHitResult clip(Level level, Vec3 eye, Vec3 from, Vec3 to, BlockHitResult blockHit) {
        if (level == null || eye == null || from.distanceToSqr(to) < 1.0E-12) return blockHit;
        List<Portal> portals = portalsNear(level, eye, Math.max(eye.distanceTo(to), from.distanceTo(to)) + 1.0);
        if (portals.isEmpty()) return blockHit;

        double best = blockHit != null && blockHit.getType() != HitResult.Type.MISS
                ? blockHit.getLocation().distanceToSqr(from) : Double.POSITIVE_INFINITY;
        RayTraceResult bestHit = null;
        for (int i = 0, n = portals.size(); i < n; i++) {
            Portal portal = portals.get(i);
            if (portal == null || portal.isRemoved() || !portal.isInFrontOfPortal(eye)) continue;
            RayTraceResult hit = portal.generalRayTrace(from, to, LENIENCY);
            if (hit == null) continue;
            double d = hit.hitPos().distanceToSqr(from);
            if (d < best) {
                best = d;
                bestHit = hit;
            }
        }
        if (bestHit == null) return blockHit;
        Vec3 pos = bestHit.hitPos();
        Vec3 n = bestHit.surfaceNormal();
        Direction face = n == null ? Direction.UP : Direction.getNearest(n.x, n.y, n.z);
        return new BlockHitResult(pos, face, BlockPos.containing(pos), false);
    }
}
