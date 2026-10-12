package dev.flyaway.perf.imm_ptl.postcard;

import dev.flyaway.perf.FlyAwayPerf;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Matrix4f;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.render.TransformationManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * Debug only ({@code imm_ptl.postcards.debug=true}): at the end of a layer-1 portal pass, log every portal near the
 * pass camera and why it is or is not drawn as a nested postcard. Re-runs Immersive Portals' own filter chain
 * ({@code PortalRenderer.shouldSkipRenderingPortal}, same order and inputs) so a single test run explains the result.
 * Rate-limited: one report per {@value #INTERVAL_S} s, at most {@value #MAX_REPORTS} reports per session.
 */
final class PostcardDiagnostics {
    private static final long INTERVAL_S = 3;
    private static final int MAX_REPORTS = 80;
    private static final double RANGE = 96;

    private static long nextNanos;
    private static int reports;

    private PostcardDiagnostics() {}

    static void onPassEnd(Portal outer, List<Portal> ipList, boolean gate, Matrix4f modelView, Matrix4f proj, Vec3 cam, long now) {
        if (now < nextNanos || reports >= MAX_REPORTS) return;
        nextNanos = now + INTERVAL_S * 1_000_000_000L;
        reports++;
        ClientLevel world = Minecraft.getInstance().level;
        if (world == null) return;
        // Same frustum and camera IP's getPortalsToRender uses in this pass.
        Frustum frustum = new Frustum(modelView, proj);
        frustum.prepare(cam.x, cam.y, cam.z);
        Vec3 isoCam = TransformationManager.getIsometricAdjustedCameraPos();

        StringBuilder sb = new StringBuilder(512);
        int near = 0;
        for (Entity en : world.entitiesForRendering()) {
            if (!(en instanceof Portal p) || p.getDistanceToNearestPointInPortal(cam) > RANGE) continue;
            near++;
            Vec3 o = p.getOriginPos(), n = p.getNormal();
            sb.append(String.format(Locale.ROOT, "%n    %s at (%.1f, %.1f, %.1f) facing (%.2f, %.2f, %.2f) dist %.1f: %s",
                    shortId(p), o.x, o.y, o.z, n.x, n.y, n.z, p.getDistanceToNearestPointInPortal(cam),
                    verdict(p, outer, ipList, gate, isoCam, frustum)));
            appendFlags(sb, p, outer);
        }
        int global = GlobalPortalStorage.getGlobalPortals(world).size();
        FlyAwayPerf.LOGGER.info(String.format(Locale.ROOT,
                "[postcards] pass end: outer %s -> %s, pass camera (%.2f, %.2f, %.2f) in %s, maxPortalLayer=%d isLaggy=%s"
                        + " (getMaxPortalLayer=%d), IP list size %d, %d portal(s) within %.0f blocks (+%d global)%s",
                shortId(outer), outer.getDestDim().location(), cam.x, cam.y, cam.z, world.dimension().location(),
                IPGlobal.maxPortalLayer, RenderStates.isLaggy, PortalRendering.getMaxPortalLayer(), ipList.size(), near, RANGE,
                global, sb));
    }

    /** IP's filter chain in its order, then ours. */
    private static String verdict(Portal p, Portal outer, List<Portal> ipList, boolean gate, Vec3 isoCam, Frustum frustum) {
        if (!p.isPortalValid()) return "REJECTED (IP): portal not valid";
        if (!p.isVisible() && IPGlobal.maxPortalLayer != 0) return "REJECTED (IP): isVisible=false";
        if (RenderStates.getRenderedPortalNum() >= IPGlobal.portalRenderLimit) return "REJECTED (IP): portalRenderLimit reached";
        if (!p.isRoughlyVisibleTo(isoCam)) return "REJECTED (IP): pass camera is behind the portal (one-sided)";
        if (outer.cannotRenderInMe(p)) return "REJECTED (IP): outer.cannotRenderInMe (lies in the outer portal's destination plane, facing back)";
        double dist = p.getDistanceToNearestPointInPortal(isoCam);
        if (dist > PortalRenderer.getRenderRange()) return "REJECTED (IP): beyond render range " + PortalRenderer.getRenderRange();
        if (IPCGlobal.earlyFrustumCullingPortal && dist > 0.1 && !frustum.isVisible(p.getThinBoundingBox())) {
            return "REJECTED (IP): outside the pass frustum";
        }
        if (PortalRendering.isInvalidRecursionRendering(p)) return "REJECTED (IP): invalid recursion";
        if (!NeoForge.EVENT_BUS.post(new PortalRenderer.PortalRenderingPredicateEvent(p)).canRender()) {
            return "REJECTED (IP): a PortalRenderingPredicateEvent listener said no";
        }
        if (!gate) return "REJECTED (postcards): IPGlobal.maxPortalLayer < 2";
        if (!ipList.contains(p)) return "REJECTED: not in IP's list although every filter passed (list built before a state change?)";
        if (!PostcardRenderer.drawable(p, outer)) return "SKIPPED (postcards): global or fuse-view portal";
        return "ACCEPTED: drawn nested";
    }

    private static void appendFlags(StringBuilder sb, Portal p, Portal outer) {
        if (p == outer) sb.append(" [is the outer portal itself]");
        if (Portal.isReversePortal(p, outer)) sb.append(" [reverse of outer]");
        if (Portal.isFlippedPortal(p, outer)) sb.append(" [flipped twin of outer]");
        Vec3 c = outer.getContentDirection(), d = outer.getDestPos(), o = p.getOriginPos();
        double side = (o.x - d.x) * c.x + (o.y - d.y) * c.y + (o.z - d.z) * c.z;
        if (side < 0) sb.append(String.format(Locale.ROOT, " [%.2f blocks on the near side of the outer destination plane: clipped]", -side));
    }

    private static String shortId(Portal p) {
        return p.getUUID().toString().substring(0, 8);
    }
}
