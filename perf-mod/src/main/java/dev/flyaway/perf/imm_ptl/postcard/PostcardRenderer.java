package dev.flyaway.perf.imm_ptl.postcard;

import static org.lwjgl.opengl.GL33C.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.flyaway.perf.FlyAwayPerf;
import dev.flyaway.perf.imm_ptl.ImmPtlTunables;
import dev.flyaway.perf.iris.IrisFboDeferral;
import java.nio.FloatBuffer;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.my_util.TriangleConsumer;

/**
 * "Portal postcards": with Iris shaders, Immersive Portals' IrisCompatibilityPortalRenderer renders only one portal
 * layer, so a portal seen through another portal shows nothing. This class
 * <ul>
 *   <li>captures, at the end of every layer-1 portal pass (at most every {@code refreshFrames} frames per portal),
 *       the portal's screen rectangle of the finished pass image (colour + depth) into a small texture pair, together
 *       with the pass camera (position, projection * view);</li>
 *   <li>in the same place (end of a layer-1 pass, i.e. exactly where IP would recurse into layer 2 without Iris),
 *       draws every nested portal visible in that pass from its postcard, reprojected to the current camera through
 *       the nested portal's transform; a swirl stands in when no postcard exists yet;</li>
 *   <li>after a portal is drawn live in the main pass, overlays its postcard with fading opacity if the portal was a
 *       postcard a moment ago (walking through the outer portal), so there is no hard pop.</li>
 * </ul>
 * Everything is raw GL on the render thread, state is saved/restored around each section, nothing is read back.
 * See docs/portal_postcards.md.
 */
public final class PostcardRenderer {
    private static final GlStateSnapshot STATE = new GlStateSnapshot();
    private static final Matrix4f VIEW_PROJ = new Matrix4f();
    private static final Matrix4f INV_VP = new Matrix4f();
    private static final Matrix4f MAIN_VIEW = new Matrix4f(), MAIN_PROJ = new Matrix4f();
    private static final float[] BODY = new float[6];
    private static final float HAND_DIST = 0.6f;
    private static int lastPassFrame = Integer.MIN_VALUE / 2;
    private static int mainMatricesFrame = Integer.MIN_VALUE / 2;
    private static int mainCaptureFrame = Integer.MIN_VALUE / 2;
    private static int nextScanFrame = Integer.MIN_VALUE / 2;
    private static final Matrix3f LIN = new Matrix3f();
    private static final Vector4f V = new Vector4f();
    private static final Vec3 EX = new Vec3(1, 0, 0), EY = new Vec3(0, 1, 0), EZ = new Vec3(0, 0, 1);
    private static final MeshCollector COLLECTOR = new MeshCollector();
    private static final long T0 = System.nanoTime();
    private static final int RECENT_FRAMES = 3;
    private static final float NESTED_DEPTH_BIAS = 2.0e-6f;
    private static final float FADE_DEPTH_BIAS = 1.0e-5f;

    // debug statistics
    private static long statMainCaptures, statCaptures, statPostcard, statSwirl, statFades, statKept, nextStatNanos;
    private static final long KEEP_COMPLETE_S = 20;
    private static int debugLines;

    private PostcardRenderer() {}

    /** Accessor surface the mixin provides (IP's protected {@code getPortalsToRender}). */
    public interface PortalLister {
        List<Portal> flyaway$portalsToRender(Matrix4f modelView);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hook 1: end of a portal pass (IrisCompatibilityPortalRenderer.onBeforeHandRendering while rendering a portal)
    // ------------------------------------------------------------------------------------------------------------

    public static void onPortalPassEnd(PortalLister lister, Matrix4f modelView) {
        if (!ImmPtlTunables.POSTCARDS || PostcardGl.isFailed()) return;
        if (PortalRendering.getPortalLayer() != 1) return;
        Portal outer = PortalRendering.getRenderingPortal();
        if (outer == null) return;
        int frame = RenderStates.frameIndex;
        long now = System.nanoTime();
        try {
            PostcardCache.checkGeneration(IrisGeneration.get());
            PostcardCache.sweep(now);
            Matrix4f proj = RenderSystem.getProjectionMatrix();
            Vec3 cam = CHelper.getCurrentCameraPos();
            lastPassFrame = frame;
            boolean captureDue = captureDue(outer, frame) && screenRect(outer, modelView, proj, cam)
                    && !keepBetterCapture(outer, rectCoverage, now);
            // Gate on the configured layer limit, not PortalRendering.getMaxPortalLayer(): the latter drops to 1 while
            // IP's lag detector (RenderStates.isLaggy) is on, but a postcard costs one textured quad, not a world pass.
            boolean gate = IPGlobal.maxPortalLayer >= 2;
            List<Portal> nested = gate ? lister.flyaway$portalsToRender(modelView) : List.of();
            boolean anyNested = false;
            for (int i = 0, n = nested.size(); i < n; i++) {
                if (drawable(nested.get(i), outer)) {
                    anyNested = true;
                    break;
                }
            }
            if (ImmPtlTunables.POSTCARD_DEBUG) PostcardDiagnostics.onPassEnd(outer, nested, gate, modelView, proj, cam, now);
            if (!captureDue && !anyNested) {
                debugSummary(now);
                return;
            }
            if (!PostcardGl.ensure()) return;
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            IrisFboDeferral.flush();
            STATE.save();
            try {
                if (captureDue) {
                    VIEW_PROJ.set(proj).mul(modelView);
                    capture(outer, main, VIEW_PROJ, proj, cam, rectX0, rectY0, rectX1, rectY1, rectCoverage, true, null, frame, now);
                }
                if (anyNested) drawNested(outer, nested, main, modelView, proj, cam, frame, now);
            } finally {
                STATE.restore();
            }
            debugSummary(now);
        } catch (Throwable t) {
            PostcardGl.disable("rendering a portal pass", t);
        }
    }

    /** Filter for nested drawing (also used by the diagnostics). */
    static boolean drawable(Portal q, Portal outer) {
        return q != null && !q.getIsGlobal() && !q.isFuseView() && q.isPortalValid();
    }

    private static boolean captureDue(Portal p, int frame) {
        if (p.getIsGlobal()) return false; // world-wrapping portals: never shown nested by us
        PostcardCache.Entry e = PostcardCache.get(p.getUUID());
        if (e == null) return true;
        if (fadeBlocksCapture(e, frame)) return false;
        return !e.captured || !e.hasGl() || e.destDim != p.getDestDim() || frame - e.capturedFrame >= ImmPtlTunables.POSTCARD_REFRESH_FRAMES;
    }

    /** Keep the old postcard while it is (about to be) faded out over the live view. */
    private static boolean fadeBlocksCapture(PostcardCache.Entry e, int frame) {
        return e.fadeStartNanos != 0 || (frame - e.lastLiveFrame > RECENT_FRAMES && frame - e.lastNestedFrame <= RECENT_FRAMES);
    }

    // Screen rectangle of the portal about to be captured, filled by screenRect().
    private static float rectX0, rectY0, rectX1, rectY1, rectCoverage;

    /**
     * Screen rectangle (NDC, clamped to the screen) of the portal's destination side seen from the pass camera, and the
     * fraction of the unclamped rectangle that is on screen. Whole screen (coverage 1) when a corner is behind the
     * camera. Returns false when no part of it is on screen.
     */
    private static boolean screenRect(Portal p, Matrix4f modelView, Matrix4f proj, Vec3 cam) {
        VIEW_PROJ.set(proj).mul(modelView);
        double hw = p.getWidth() / 2, hh = p.getHeight() / 2;
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            Vec3 c = p.transformPoint(p.getPointInPlane((i & 1) == 0 ? -hw : hw, (i & 2) == 0 ? -hh : hh));
            V.set((float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z), 1f);
            VIEW_PROJ.transform(V);
            if (V.w < 0.05f) {
                rectX0 = rectY0 = -1f;
                rectX1 = rectY1 = 1f;
                rectCoverage = 1f;
                return true;
            }
            float nx = V.x / V.w, ny = V.y / V.w;
            x0 = Math.min(x0, nx);
            y0 = Math.min(y0, ny);
            x1 = Math.max(x1, nx);
            y1 = Math.max(y1, ny);
        }
        float cx0 = Math.max(-1f, x0), cy0 = Math.max(-1f, y0), cx1 = Math.min(1f, x1), cy1 = Math.min(1f, y1);
        if (cx1 <= cx0 || cy1 <= cy0) return false;
        double full = (double) (x1 - x0) * (y1 - y0);
        rectCoverage = full <= 0 ? 0f : (float) Math.min(1.0, (double) (cx1 - cx0) * (cy1 - cy0) / full);
        rectX0 = cx0;
        rectY0 = cy0;
        rectX1 = cx1;
        rectY1 = cy1;
        return true;
    }

    /**
     * A portal that is half off-screen gives a postcard that covers only part of the portal, and a capture from the
     * player's own view is only an approximation of the view through the portal. So a worse capture (lower score)
     * does not replace a better one for {@value #KEEP_COMPLETE_S} s; the content goes a little stale instead.
     */
    private static boolean keepBetterCapture(Portal p, float score, long now) {
        PostcardCache.Entry e = PostcardCache.get(p.getUUID());
        if (e == null || !postcardValid(e, p)) return false;
        if (score + 0.05f >= e.score || now - e.capturedNanos > KEEP_COMPLETE_S * 1_000_000_000L) return false;
        statKept++;
        return true;
    }

    /**
     * Copies the NDC rectangle (x0,y0)-(x1,y1) of the main target into p's postcard, stretched over the whole texture.
     * {@code viewProj} is the camera-relative projection * view of the image in the main target; {@code body} is the
     * player's body segment (feet, head; camera relative) to mask out, or null.
     */
    private static void capture(Portal p, RenderTarget main, Matrix4f viewProj, Matrix4f proj, Vec3 cam, float x0, float y0, float x1,
                                float y1, float score, boolean direct, float[] body, int frame, long now) {
        int W = main.width, H = main.height;
        if (W <= 0 || H <= 0) return;
        int px0 = Math.max(0, (int) Math.floor((x0 * 0.5f + 0.5f) * W) - 2);
        int py0 = Math.max(0, (int) Math.floor((y0 * 0.5f + 0.5f) * H) - 2);
        int px1 = Math.min(W, (int) Math.ceil((x1 * 0.5f + 0.5f) * W) + 2);
        int py1 = Math.min(H, (int) Math.ceil((y1 * 0.5f + 0.5f) * H) + 2);
        int pw = px1 - px0, ph = py1 - py0;
        if (pw < 4 || ph < 4) return;

        PostcardCache.Entry e = PostcardCache.getOrCreate(p.getUUID());
        bindEntity(e, p);
        e.destDim = p.getDestDim();
        int res = ImmPtlTunables.POSTCARD_RESOLUTION;
        if (!e.hasGl() || e.res != res) {
            PostcardCache.makeRoomFor(e);
            PostcardGl.allocate(e, res);
        }
        // The whole texture is used (non-uniform stretch), so the mip chain never mixes in unused texels.
        int uw = res, uh = res;

        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        glViewport(0, 0, uw, uh);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_CLIP_DISTANCE0);
        glColorMask(true, true, true, true);
        glUseProgram(PostcardGl.captureProgram);
        glBindVertexArray(PostcardGl.emptyVao);
        bindTex(GlStateSnapshot.UNIT_A, main.getColorTextureId(), PostcardGl.linearSampler);
        bindTex(GlStateSnapshot.UNIT_B, main.getDepthTextureId(), PostcardGl.nearestSampler);
        glUniform1i(PostcardGl.cSrcColor, GlStateSnapshot.UNIT_A);
        glUniform1i(PostcardGl.cSrcDepth, GlStateSnapshot.UNIT_B);
        glUniform4f(PostcardGl.cSrcRect, (float) px0 / W, (float) py0 / H, (float) pw / W, (float) ph / H);
        glUniform2f(PostcardGl.cSrcSize, W, H);
        glUniform2f(PostcardGl.cDstSize, uw, uh);
        glUniform2f(PostcardGl.cProjAB, proj.m22(), proj.m32());
        INV_VP.set(viewProj).invert();
        PostcardGl.uniformMat4(PostcardGl.cInvVP, INV_VP);
        if (body != null) {
            glUniform3f(PostcardGl.cBodyA, body[0], body[1], body[2]);
            glUniform3f(PostcardGl.cBodyB, body[3], body[4], body[5]);
        } else {
            glUniform3f(PostcardGl.cBodyA, 0f, 0f, 0f);
            glUniform3f(PostcardGl.cBodyB, 0f, 0f, 0f);
        }
        glUniform1f(PostcardGl.cMinDist, direct ? 0f : HAND_DIST);
        glDrawArrays(GL_TRIANGLES, 0, 3);
        PostcardGl.generateMips(e);

        e.capVP.set(viewProj);
        e.camX = cam.x;
        e.camY = cam.y;
        e.camZ = cam.z;
        e.projA = proj.m22();
        e.projB = proj.m32();
        e.rx0 = (float) px0 / W * 2 - 1;
        e.ry0 = (float) py0 / H * 2 - 1;
        e.rx1 = (float) px1 / W * 2 - 1;
        e.ry1 = (float) py1 / H * 2 - 1;
        e.usedW = uw;
        e.usedH = uh;
        Vec3 d = p.getDestPos();
        e.destX = d.x;
        e.destY = d.y;
        e.destZ = d.z;
        e.capturedFrame = frame;
        e.capturedNanos = now;
        e.score = score;
        e.direct = direct;
        e.captured = true;
        if (direct) e.lastUsedNanos = now;
        if (direct) statCaptures++;
        else statMainCaptures++;
        if (ImmPtlTunables.POSTCARD_DEBUG && debugLines < 150) {
            debugLines++;
            FlyAwayPerf.LOGGER.info("[postcards] {} capture {} -> {} rect {}x{} at {},{} score {} (frame {})", direct ? "direct" : "main-view",
                    p.getUUID(), p.getDestDim().location(), pw, ph, px0, py0, String.format("%.2f", score), frame);
        }
    }

    private static void drawNested(Portal outer, List<Portal> nested, RenderTarget main, Matrix4f modelView, Matrix4f proj, Vec3 cam,
                                   int frame, long now) {
        glBindFramebuffer(GL_FRAMEBUFFER, main.frameBufferId);
        glViewport(0, 0, main.width, main.height);
        // Clip plane of the outer portal's destination side: nothing between the pass camera and that plane exists.
        Vec3 n = outer.getContentDirection();
        Vec3 dp = outer.getDestPos();
        float clipW = (float) (n.x * (cam.x - dp.x) + n.y * (cam.y - dp.y) + n.z * (cam.z - dp.z));
        beginDisplay(modelView, proj, (float) n.x, (float) n.y, (float) n.z, clipW, NESTED_DEPTH_BIAS);
        for (int i = 0, c = nested.size(); i < c; i++) {
            Portal q = nested.get(i);
            if (!drawable(q, outer)) continue;
            drawSurface(q, cam, frame, now, 1f, true);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hook 1b: the player's own view. A portal Q's view is "the destination world, seen from Q's destination point,
    // looking along Q's content direction". When the player stands near Q's destination and looks that way (typically
    // right after walking out of Q, or out of Q's reverse), the main view IS (nearly) that view, so it is captured
    // as Q's postcard. Without this, a portal only had a postcard after being looked at directly.
    // ------------------------------------------------------------------------------------------------------------

    /** Main view matrices, from GameRenderer.renderLevel's call of LevelRenderer.renderLevel (main pass only). */
    public static void onMainLevelMatrices(Matrix4f modelView, Matrix4f projection) {
        MAIN_VIEW.set(modelView);
        MAIN_PROJ.set(projection);
        mainMatricesFrame = RenderStates.frameIndex;
    }

    /**
     * The main view's image is finished in the main target and its depth is still the main view's (called right
     * after LevelRenderer.renderLevel, and in IrisCompatibilityPortalRenderer before its portal passes; whichever
     * comes first in a frame without portal passes does the work).
     */
    public static void onMainViewFinished() {
        if (!ImmPtlTunables.POSTCARDS || PostcardGl.isFailed() || PortalRendering.isRendering()) return;
        // Postcards are only shown by the Iris compatibility renderer (without shaders IP renders real layers).
        if (!(qouteall.imm_ptl.core.IPCGlobal.renderer instanceof qouteall.imm_ptl.core.compat.iris_compatibility.IrisCompatibilityPortalRenderer)) return;
        int frame = RenderStates.frameIndex;
        if (mainCaptureFrame == frame || mainMatricesFrame != frame || lastPassFrame == frame) return;
        mainCaptureFrame = frame;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ClientWorldLoader.getIsInitialized()) return;
        long now = System.nanoTime();
        try {
            PostcardCache.checkGeneration(IrisGeneration.get());
            PostcardCache.sweep(now);
            Vec3 cam = CHelper.getCurrentCameraPos();
            if (frame >= nextScanFrame || frame < nextScanFrame - SCAN_FRAMES) {
                nextScanFrame = frame + SCAN_FRAMES;
                scanCandidates(mc.level.dimension(), cam);
            }
            if (CANDIDATES.isEmpty()) return;
            Camera camera = mc.gameRenderer.getMainCamera();
            Vector3f look = camera.getLookVector();
            Portal best = null;
            float bestScore = 0f;
            for (int i = 0, n = CANDIDATES.size(); i < n; i++) {
                Portal q = CANDIDATES.get(i);
                if (q.isRemoved() || q.getDestDim() != mc.level.dimension()) continue;
                float sc = mainViewScore(q, cam, look);
                if (sc <= bestScore || !mainCaptureDue(q, sc, frame, now)) continue;
                best = q;
                bestScore = sc;
            }
            if (best == null) return;
            PostcardCache.Entry e = PostcardCache.get(best.getUUID());
            if ((e == null || !e.hasGl()) && !PostcardCache.hasFreeGlSlot()) return; // never evict for a main-view capture
            if (!PostcardGl.ensure()) return;
            float[] body = null;
            if (camera.isDetached() && camera.getEntity() != null) {
                Vec3 f = camera.getEntity().getPosition(mc.getTimer().getGameTimeDeltaPartialTick(true));
                double h = camera.getEntity().getBbHeight();
                BODY[0] = (float) (f.x - cam.x);
                BODY[1] = (float) (f.y + 0.3 - cam.y);
                BODY[2] = (float) (f.z - cam.z);
                BODY[3] = BODY[0];
                BODY[4] = (float) (f.y + Math.max(0.3, h - 0.3) - cam.y) + 0.001f;
                BODY[5] = BODY[2];
                body = BODY;
            }
            RenderTarget main = mc.getMainRenderTarget();
            IrisFboDeferral.flush();
            STATE.save();
            try {
                VIEW_PROJ.set(MAIN_PROJ).mul(MAIN_VIEW);
                capture(best, main, VIEW_PROJ, MAIN_PROJ, cam, -1f, -1f, 1f, 1f, bestScore, false, body, frame, now);
            } finally {
                STATE.restore();
            }
            PostcardCache.Entry done = PostcardCache.get(best.getUUID());
            if (done != null) done.lastMainTryFrame = frame;
        } catch (Throwable t) {
            PostcardGl.disable("capturing the main view", t);
        }
    }

    private static final int SCAN_FRAMES = 20;
    private static final double SCAN_RANGE = 48;
    private static final java.util.ArrayList<Portal> CANDIDATES = new java.util.ArrayList<>();

    /** Portals in any loaded client world whose destination is in this world, near the camera. */
    private static void scanCandidates(net.minecraft.resources.ResourceKey<Level> here, Vec3 cam) {
        CANDIDATES.clear();
        for (ClientLevel w : ClientWorldLoader.getClientWorlds()) {
            for (net.minecraft.world.entity.Entity en : w.entitiesForRendering()) {
                if (!(en instanceof Portal q) || q.getIsGlobal() || q.isFuseView() || q.getDestDim() != here) continue;
                if (q.getDestPos().distanceToSqr(cam) > SCAN_RANGE * SCAN_RANGE) continue;
                CANDIDATES.add(q);
            }
        }
    }

    /**
     * How well the player's view stands in for the view through q: 0 = not at all. Needs the camera on the content
     * side of q's destination plane (or barely behind it), looking roughly along the content direction, close to the
     * destination. A direct capture of a fully visible portal scores 1; this tops out at 0.9.
     */
    private static float mainViewScore(Portal q, Vec3 cam, Vector3f look) {
        Vec3 n = q.getContentDirection();
        Vec3 d = q.getDestPos();
        double dx = cam.x - d.x, dy = cam.y - d.y, dz = cam.z - d.z;
        double along = dx * n.x + dy * n.y + dz * n.z;
        if (along < -0.3) return 0f;
        double facing = look.x() * n.x + look.y() * n.y + look.z() * n.z;
        if (facing < 0.45) return 0f;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double lateral = Math.sqrt(Math.max(0, dist * dist - along * along));
        double half = Math.max(q.getWidth(), q.getHeight()) * 0.5;
        double f = Math.min(1.0, (facing - 0.45) / 0.45);
        double sc = 0.9 * f * f * (3 - 2 * f) / (1 + 0.05 * Math.max(0, along - 1) + 0.25 * Math.max(0, lateral - half));
        return sc < 0.15 ? 0f : (float) sc;
    }

    private static boolean mainCaptureDue(Portal q, float score, int frame, long now) {
        PostcardCache.Entry e = PostcardCache.get(q.getUUID());
        if (e == null) return true;
        if (fadeBlocksCapture(e, frame)) return false;
        if (frame - e.lastMainTryFrame < ImmPtlTunables.POSTCARD_REFRESH_FRAMES * 2 && frame >= e.lastMainTryFrame) return false;
        if (!postcardValid(e, q)) return true;
        if (frame - e.capturedFrame < ImmPtlTunables.POSTCARD_REFRESH_FRAMES) return false;
        return score + 0.05f >= e.score || now - e.capturedNanos > KEEP_COMPLETE_S * 1_000_000_000L;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Hook 2: a portal was just drawn live in the main pass (doRenderPortal after drawPortalAreaWithFramebuffer)
    // ------------------------------------------------------------------------------------------------------------

    public static void onLivePortalDrawn(Portal q, Matrix4f modelView) {
        if (!ImmPtlTunables.POSTCARDS || PostcardGl.isFailed() || PortalRendering.isRendering()) return;
        PostcardCache.Entry e = PostcardCache.get(q.getUUID());
        if (e == null) return;
        int frame = RenderStates.frameIndex;
        long now = System.nanoTime();
        boolean wasLive = frame - e.lastLiveFrame <= RECENT_FRAMES;
        e.lastLiveFrame = frame;
        try {
            if (e.fadeStartNanos == 0) {
                if (wasLive || frame - e.lastNestedFrame > RECENT_FRAMES || ImmPtlTunables.POSTCARD_FADE_MS == 0) return;
                if (!postcardValid(e, q)) return;
                e.fadeStartNanos = now;
                statFades++;
                if (ImmPtlTunables.POSTCARD_DEBUG) FlyAwayPerf.LOGGER.info("[postcards] fade postcard -> live view for {}", q.getUUID());
            }
            float k = (now - e.fadeStartNanos) / 1.0e6f / ImmPtlTunables.POSTCARD_FADE_MS;
            if (k >= 1f) {
                e.fadeStartNanos = 0;
                e.lastNestedFrame = Integer.MIN_VALUE / 2;
                return;
            }
            if (!PostcardGl.ensure()) return;
            float alpha = 1f - k * k * (3f - 2f * k);
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            Vec3 cam = CHelper.getCurrentCameraPos();
            IrisFboDeferral.flush();
            STATE.save();
            try {
                // Draw into whatever IP bound (its deferred buffer); same size as the main target.
                glViewport(0, 0, main.width, main.height);
                beginDisplay(modelView, RenderSystem.getProjectionMatrix(), 0f, 0f, 0f, 1f, FADE_DEPTH_BIAS);
                drawSurface(q, cam, frame, now, alpha, false);
            } finally {
                STATE.restore();
            }
        } catch (Throwable t) {
            PostcardGl.disable("fading a postcard", t);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static void beginDisplay(Matrix4f modelView, Matrix4f proj, float cx, float cy, float cz, float cw, float depthBias) {
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LEQUAL);
        glDepthMask(false);
        glEnable(GL_BLEND);
        glBlendFuncSeparate(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_ZERO, GL_ONE);
        glBlendEquationSeparate(GL_FUNC_ADD, GL_FUNC_ADD);
        glColorMask(true, true, true, false);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_CLIP_DISTANCE0);
        glEnable(GL_DEPTH_CLAMP);
        glUseProgram(PostcardGl.displayProgram);
        glBindVertexArray(PostcardGl.meshVao);
        VIEW_PROJ.set(proj).mul(modelView);
        PostcardGl.uniformMat4(PostcardGl.dViewProj, VIEW_PROJ);
        glUniform1i(PostcardGl.dColor, GlStateSnapshot.UNIT_A);
        glUniform1i(PostcardGl.dDepth, GlStateSnapshot.UNIT_B);
        glUniform4f(PostcardGl.dClip, cx, cy, cz, cw);
        glUniform1f(PostcardGl.dTime, ((System.nanoTime() - T0) % 3_600_000_000_000L) / 1.0e9f);
        glUniform1f(PostcardGl.dDepthBias, depthBias);
        glUniform1i(PostcardGl.dDebug, ImmPtlTunables.POSTCARD_DEBUG ? 1 : 0);
    }

    private static boolean postcardValid(PostcardCache.Entry e, Portal q) {
        if (!e.captured || !e.hasGl() || e.destDim != q.getDestDim()) return false;
        Vec3 d = q.getDestPos();
        double dx = d.x - e.destX, dy = d.y - e.destY, dz = d.z - e.destZ;
        return dx * dx + dy * dy + dz * dz < 0.25;
    }

    private static void drawSurface(Portal q, Vec3 cam, int frame, long now, float alpha, boolean nested) {
        PostcardCache.Entry e = PostcardCache.getOrCreate(q.getUUID());
        bindEntity(e, q);
        updateTint(e, q, frame);
        boolean valid = postcardValid(e, q);
        glUniform1i(PostcardGl.dMode, valid ? 0 : 1);
        glUniform1f(PostcardGl.dAlpha, alpha);
        if (valid) {
            if (e.firstShownNanos == 0) e.firstShownNanos = now;
            float mix = ImmPtlTunables.POSTCARD_FADE_MS == 0 || !nested ? 1f
                    : Math.min(1f, (now - e.firstShownNanos) / 1.0e6f / ImmPtlTunables.POSTCARD_FADE_MS);
            glUniform1f(PostcardGl.dMix, mix);
            bindTex(GlStateSnapshot.UNIT_A, e.colorTex, PostcardGl.mipSampler);
            bindTex(GlStateSnapshot.UNIT_B, e.depthTex, PostcardGl.mipSampler);
            glUniform1f(PostcardGl.dDispLod, Math.max(0f, (float) (Math.log(e.res / 96.0) / Math.log(2))));
            Vec3 ex = q.transformLocalVec(EX), ey = q.transformLocalVec(EY), ez = q.transformLocalVec(EZ);
            LIN.set((float) ex.x, (float) ex.y, (float) ex.z, (float) ey.x, (float) ey.y, (float) ey.z, (float) ez.x, (float) ez.y, (float) ez.z);
            PostcardGl.uniformMat3(PostcardGl.dQLin, LIN);
            Vec3 tc = q.transformPoint(cam);
            glUniform3f(PostcardGl.dQOff, (float) (tc.x - e.camX), (float) (tc.y - e.camY), (float) (tc.z - e.camZ));
            PostcardGl.uniformMat4(PostcardGl.dCapVP, e.capVP);
            glUniform4f(PostcardGl.dCapRect, e.rx0, e.ry0, e.rx1, e.ry1);
            glUniform2f(PostcardGl.dTexScale, 1f, 1f);
            glUniform2f(PostcardGl.dTexelHalf, 0.5f / e.res, 0.5f / e.res);
            glUniform2f(PostcardGl.dCapAB, e.projA, e.projB);
            e.lastUsedNanos = now;
        } else {
            e.firstShownNanos = 0;
        }
        Vec3 o = q.getOriginPos();
        float ox = (float) (o.x - cam.x), oy = (float) (o.y - cam.y), oz = (float) (o.z - cam.z);
        glUniform3f(PostcardGl.dOriginRel, ox, oy, oz);
        Vec3 aw = q.getAxisW(), ah = q.getAxisH();
        glUniform3f(PostcardGl.dAxisW, (float) aw.x, (float) aw.y, (float) aw.z);
        glUniform3f(PostcardGl.dAxisH, (float) ah.x, (float) ah.y, (float) ah.z);
        glUniform2f(PostcardGl.dHalfSize, (float) Math.max(1e-3, q.getWidth() / 2), (float) Math.max(1e-3, q.getHeight() / 2));
        glUniform3f(PostcardGl.dTint, e.tintR, e.tintG, e.tintB);

        COLLECTOR.begin();
        q.renderViewAreaMesh(new Vec3(ox, oy, oz), COLLECTOR);
        int verts = COLLECTOR.end();
        if (verts > 0) {
            glBindBuffer(GL_ARRAY_BUFFER, PostcardGl.meshVbo);
            glBufferData(GL_ARRAY_BUFFER, PostcardGl.mesh, GL_STREAM_DRAW);
            glDrawArrays(GL_TRIANGLES, 0, verts);
        }
        if (nested) {
            e.lastNestedFrame = frame;
            if (valid) statPostcard++;
            else statSwirl++;
        }
        if (ImmPtlTunables.POSTCARD_DEBUG && debugLines < 150) {
            debugLines++;
            FlyAwayPerf.LOGGER.info("[postcards] draw {} {} as {} (alpha {}, {} vertices, frame {})", nested ? "nested" : "fade-out",
                    q.getUUID(), valid ? "postcard" : "swirl", alpha, verts, frame);
        }
    }

    private static void bindEntity(PostcardCache.Entry e, Portal p) {
        if (e.portal.get() != p) e.portal = new java.lang.ref.WeakReference<>(p);
        e.srcDim = p.level().dimension();
        e.destDim = e.captured ? e.destDim : p.getDestDim();
        if (e.lastUsedNanos == 0) e.lastUsedNanos = System.nanoTime();
    }

    private static void bindTex(int unit, int tex, int sampler) {
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, tex);
        glBindSampler(unit, sampler);
    }

    /** Destination fog colour (cheap, refreshed every 100 frames), blended a little towards portal purple. */
    private static void updateTint(PostcardCache.Entry e, Portal q, int frame) {
        if (frame - e.tintFrame < 100 && frame >= e.tintFrame) return;
        e.tintFrame = frame;
        int fog = -1;
        if (ClientWorldLoader.getIsInitialized()) {
            for (ClientLevel w : ClientWorldLoader.getClientWorlds()) {
                if (w.dimension() != q.getDestDim()) continue;
                BlockPos bp = BlockPos.containing(q.getDestPos());
                if (w.hasChunk(bp.getX() >> 4, bp.getZ() >> 4)) fog = w.getBiome(bp).value().getFogColor();
                break;
            }
        }
        if (fog == -1) {
            if (q.getDestDim() == Level.NETHER) fog = 0x330808;
            else if (q.getDestDim() == Level.END) fog = 0x0A0814;
            else fog = 0xC0D8FF;
        }
        float r = ((fog >> 16) & 255) / 255f, g = ((fog >> 8) & 255) / 255f, b = (fog & 255) / 255f;
        e.tintR = r * 0.85f + 0.55f * 0.15f;
        e.tintG = g * 0.85f + 0.30f * 0.15f;
        e.tintB = b * 0.85f + 0.85f * 0.15f;
    }

    private static void debugSummary(long now) {
        if (!ImmPtlTunables.POSTCARD_DEBUG || now < nextStatNanos) return;
        nextStatNanos = now + 5_000_000_000L;
        FlyAwayPerf.LOGGER.info("[postcards] last 5 s: direct captures={} main-view captures={} (skipped, kept a better one: {}) nested draws postcard={} membrane={} fades={} entries={}",
                statCaptures, statMainCaptures, statKept, statPostcard, statSwirl, statFades, PostcardCache.size());
        statCaptures = statMainCaptures = statPostcard = statSwirl = statFades = statKept = 0;
    }

    /** Collects portal surface triangles into {@link PostcardGl#mesh}; reused, no per-call allocation. */
    private static final class MeshCollector implements TriangleConsumer {
        private FloatBuffer buf;

        void begin() {
            buf = PostcardGl.mesh;
            buf.clear();
        }

        int end() {
            buf.flip();
            PostcardGl.mesh = buf;
            return buf.limit() / 3;
        }

        @Override
        public void accept(double ax, double ay, double az, double bx, double by, double bz, double cx, double cy, double cz) {
            if (buf.remaining() < 9) {
                FloatBuffer n = MemoryUtil.memAllocFloat(buf.capacity() * 2);
                buf.flip();
                n.put(buf);
                MemoryUtil.memFree(buf);
                buf = n;
            }
            buf.put((float) ax).put((float) ay).put((float) az)
               .put((float) bx).put((float) by).put((float) bz)
               .put((float) cx).put((float) cy).put((float) cz);
        }
    }
}
