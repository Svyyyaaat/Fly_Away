package dev.flyaway.perf.imm_ptl.postcard;

import static org.lwjgl.opengl.GL33C.*;

import java.nio.IntBuffer;
import org.lwjgl.BufferUtils;

/**
 * Saves and restores every piece of GL state the postcard code touches, with raw GL. Restoring the exact previous
 * values keeps the caches of GlStateManager, BufferUploader and ShaderInstance valid (they never see a change).
 * Render thread only; one preallocated instance, no allocation per use.
 * <p>
 * Cost: about 35 glGet* calls per save. A save happens at most once per portal pass and only when a postcard is
 * captured or drawn in that pass (see docs/portal_postcards.md for why queries rather than GlStateManager's caches).
 */
final class GlStateSnapshot {
    static final int UNIT_A = 0;
    static final int UNIT_B = 1;

    private final IntBuffer ib = BufferUtils.createIntBuffer(16);

    private int program, vao, arrayBuffer, drawFbo, readFbo, activeTexture;
    private final int[] viewport = new int[4];
    private int texA, texB, samplerA, samplerB;
    private boolean blend, depthTest, cull, scissor, stencil, depthClamp, clip0;
    private int depthFunc;
    private boolean depthMask;
    private final boolean[] colorMask = new boolean[4];
    private int blendSrcRgb, blendDstRgb, blendSrcA, blendDstA, blendEqRgb, blendEqA;

    void save() {
        program = glGetInteger(GL_CURRENT_PROGRAM);
        vao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        arrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        drawFbo = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
        readFbo = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
        ib.clear();
        glGetIntegerv(GL_VIEWPORT, ib);
        for (int i = 0; i < 4; i++) viewport[i] = ib.get(i);
        activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
        glActiveTexture(GL_TEXTURE0 + UNIT_A);
        texA = glGetInteger(GL_TEXTURE_BINDING_2D);
        samplerA = glGetInteger(GL_SAMPLER_BINDING);
        glActiveTexture(GL_TEXTURE0 + UNIT_B);
        texB = glGetInteger(GL_TEXTURE_BINDING_2D);
        samplerB = glGetInteger(GL_SAMPLER_BINDING);
        glActiveTexture(activeTexture);
        blend = glIsEnabled(GL_BLEND);
        depthTest = glIsEnabled(GL_DEPTH_TEST);
        cull = glIsEnabled(GL_CULL_FACE);
        scissor = glIsEnabled(GL_SCISSOR_TEST);
        stencil = glIsEnabled(GL_STENCIL_TEST);
        depthClamp = glIsEnabled(GL_DEPTH_CLAMP);
        clip0 = glIsEnabled(GL_CLIP_DISTANCE0);
        depthFunc = glGetInteger(GL_DEPTH_FUNC);
        depthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
        ib.clear();
        glGetIntegerv(GL_COLOR_WRITEMASK, ib);
        for (int i = 0; i < 4; i++) colorMask[i] = ib.get(i) != 0;
        blendSrcRgb = glGetInteger(GL_BLEND_SRC_RGB);
        blendDstRgb = glGetInteger(GL_BLEND_DST_RGB);
        blendSrcA = glGetInteger(GL_BLEND_SRC_ALPHA);
        blendDstA = glGetInteger(GL_BLEND_DST_ALPHA);
        blendEqRgb = glGetInteger(GL_BLEND_EQUATION_RGB);
        blendEqA = glGetInteger(GL_BLEND_EQUATION_ALPHA);
    }

    void restore() {
        glUseProgram(program);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFbo);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, readFbo);
        glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        glActiveTexture(GL_TEXTURE0 + UNIT_A);
        glBindTexture(GL_TEXTURE_2D, texA);
        glBindSampler(UNIT_A, samplerA);
        glActiveTexture(GL_TEXTURE0 + UNIT_B);
        glBindTexture(GL_TEXTURE_2D, texB);
        glBindSampler(UNIT_B, samplerB);
        glActiveTexture(activeTexture);
        set(GL_BLEND, blend);
        set(GL_DEPTH_TEST, depthTest);
        set(GL_CULL_FACE, cull);
        set(GL_SCISSOR_TEST, scissor);
        set(GL_STENCIL_TEST, stencil);
        set(GL_DEPTH_CLAMP, depthClamp);
        set(GL_CLIP_DISTANCE0, clip0);
        glDepthFunc(depthFunc);
        glDepthMask(depthMask);
        glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcA, blendDstA);
        glBlendEquationSeparate(blendEqRgb, blendEqA);
    }

    private static void set(int cap, boolean on) {
        if (on) glEnable(cap);
        else glDisable(cap);
    }
}
