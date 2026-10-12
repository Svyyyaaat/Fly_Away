package dev.flyaway.perf.imm_ptl.postcard;

import static org.lwjgl.opengl.GL33C.*;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.flyaway.perf.FlyAwayPerf;
import dev.flyaway.perf.imm_ptl.ImmPtlTunables;
import dev.flyaway.perf.iris.IrisFboDeferral;
import java.nio.FloatBuffer;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
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
    private static final Matrix3f LIN = new Matrix3f();
    private static final Vector4f V = new Vector4f();
    private static final Vec3 EX = new Vec3(1, 0, 0), EY = new Vec3(0, 1, 0), EZ = new Vec3(0, 0, 1);
    private static final MeshCollector COLLECTOR = new MeshCollector();
    private static final long T0 = System.nanoTime();
    private static final int RECENT_FRAMES = 3;
    private static final float NESTED_DEPTH_BIAS = 2.0e-6f;
    private static final float FADE_DEPTH_BIAS = 1.0e-5f;

    // debug statistics
    private static long statCaptures, statPostcard, statSwirl, statFades, nextStatNanos;
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
            boolean captureDue = captureDue(outer, frame);
            List<Portal> nested = PortalRendering.getMaxPortalLayer() >= 2 ? lister.flyaway$portalsToRender(modelView) : List.of();
            boolean anyNested = false;
            for (int i = 0, n = nested.size(); i < n; i++) {
                if (drawable(nested.get(i), outer)) {
                    anyNested = true;
                    break;
                }
            }
            if (!captureDue && !anyNested) return;
            if (!PostcardGl.ensure()) return;
            Matrix4f proj = RenderSystem.getProjectionMatrix();
            Vec3 cam = CHelper.getCurrentCameraPos();
            RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
            IrisFboDeferral.flush();
            STATE.save();
            try {
                if (captureDue) capture(outer, main, modelView, proj, cam, frame, now);
                if (anyNested) drawNested(outer, nested, main, modelView, proj, cam, frame, now);
            } finally {
                STATE.restore();
            }
            debugSummary(now);
        } catch (Throwable t) {
            PostcardGl.disable("rendering a portal pass", t);
        }
    }

    private static boolean drawable(Portal q, Portal outer) {
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

    private static void capture(Portal p, RenderTarget main, Matrix4f modelView, Matrix4f proj, Vec3 cam, int frame, long now) {
        int W = main.width, H = main.height;
        if (W <= 0 || H <= 0) return;
        VIEW_PROJ.set(proj).mul(modelView);
        // Screen rectangle of the portal's destination side, seen from the pass camera.
        double hw = p.getWidth() / 2, hh = p.getHeight() / 2;
        float x0 = 1, y0 = 1, x1 = -1, y1 = -1;
        boolean full = false;
        for (int i = 0; i < 4; i++) {
            Vec3 c = p.transformPoint(p.getPointInPlane((i & 1) == 0 ? -hw : hw, (i & 2) == 0 ? -hh : hh));
            V.set((float) (c.x - cam.x), (float) (c.y - cam.y), (float) (c.z - cam.z), 1f);
            VIEW_PROJ.transform(V);
            if (V.w < 0.05f) {
                full = true;
                break;
            }
            float nx = V.x / V.w, ny = V.y / V.w;
            x0 = Math.min(x0, nx);
            y0 = Math.min(y0, ny);
            x1 = Math.max(x1, nx);
            y1 = Math.max(y1, ny);
        }
        if (full) {
            x0 = y0 = -1;
            x1 = y1 = 1;
        }
        int px0 = Math.max(0, (int) Math.floor((Math.max(-1f, x0) * 0.5f + 0.5f) * W) - 2);
        int py0 = Math.max(0, (int) Math.floor((Math.max(-1f, y0) * 0.5f + 0.5f) * H) - 2);
        int px1 = Math.min(W, (int) Math.ceil((Math.min(1f, x1) * 0.5f + 0.5f) * W) + 2);
        int py1 = Math.min(H, (int) Math.ceil((Math.min(1f, y1) * 0.5f + 0.5f) * H) + 2);
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
        float scale = Math.min(1f, (float) res / Math.max(pw, ph));
        int uw = Math.max(1, Math.min(res, Math.round(pw * scale)));
        int uh = Math.max(1, Math.min(res, Math.round(ph * scale)));

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
        glDrawArrays(GL_TRIANGLES, 0, 3);

        e.capVP.set(VIEW_PROJ);
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
        e.captured = true;
        e.lastUsedNanos = now;
        statCaptures++;
        if (ImmPtlTunables.POSTCARD_DEBUG && debugLines < 40) {
            debugLines++;
            FlyAwayPerf.LOGGER.info("[postcards] capture {} -> {} rect {}x{} at {},{} into {}x{} (frame {})",
                    p.getUUID(), p.getDestDim().location(), pw, ph, px0, py0, uw, uh, frame);
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
            bindTex(GlStateSnapshot.UNIT_A, e.colorTex, PostcardGl.linearSampler);
            bindTex(GlStateSnapshot.UNIT_B, e.depthTex, PostcardGl.nearestSampler);
            Vec3 ex = q.transformLocalVec(EX), ey = q.transformLocalVec(EY), ez = q.transformLocalVec(EZ);
            LIN.set((float) ex.x, (float) ex.y, (float) ex.z, (float) ey.x, (float) ey.y, (float) ey.z, (float) ez.x, (float) ez.y, (float) ez.z);
            PostcardGl.uniformMat3(PostcardGl.dQLin, LIN);
            Vec3 tc = q.transformPoint(cam);
            glUniform3f(PostcardGl.dQOff, (float) (tc.x - e.camX), (float) (tc.y - e.camY), (float) (tc.z - e.camZ));
            PostcardGl.uniformMat4(PostcardGl.dCapVP, e.capVP);
            glUniform4f(PostcardGl.dCapRect, e.rx0, e.ry0, e.rx1, e.ry1);
            glUniform2f(PostcardGl.dTexScale, (float) e.usedW / e.res, (float) e.usedH / e.res);
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
        if (ImmPtlTunables.POSTCARD_DEBUG && debugLines < 40) {
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

    /** Destination fog colour (cheap, refreshed every 100 frames), blended towards portal purple. */
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
        e.tintR = r * 0.65f + 0.55f * 0.35f;
        e.tintG = g * 0.65f + 0.30f * 0.35f;
        e.tintB = b * 0.65f + 0.85f * 0.35f;
    }

    private static void debugSummary(long now) {
        if (!ImmPtlTunables.POSTCARD_DEBUG || now < nextStatNanos) return;
        nextStatNanos = now + 5_000_000_000L;
        FlyAwayPerf.LOGGER.info("[postcards] last 5 s: captures={} nested draws postcard={} swirl={} fades={} entries={}",
                statCaptures, statPostcard, statSwirl, statFades, PostcardCache.size());
        statCaptures = statPostcard = statSwirl = statFades = 0;
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
