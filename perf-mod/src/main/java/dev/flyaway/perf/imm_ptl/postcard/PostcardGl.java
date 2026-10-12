package dev.flyaway.perf.imm_ptl.postcard;

import static org.lwjgl.opengl.GL33C.*;

import dev.flyaway.perf.FlyAwayPerf;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Raw GL objects of the postcard feature: two programs compiled from assets/flyaway_perf/shaders (GLSL 330 core,
 * deliberately NOT registered as vanilla core shaders, so Iris never sees or patches them), an empty VAO for the
 * fullscreen capture triangle, a VAO/VBO for portal surface triangles, and two sampler objects (so whatever texture
 * parameters or sampler objects Iris left on the units cannot change how we sample). Render thread only.
 */
final class PostcardGl {
    private static boolean initDone;
    private static boolean failed;

    static int captureProgram, displayProgram;
    static int emptyVao, meshVao, meshVbo;
    static int linearSampler, nearestSampler, mipSampler;

    // capture uniforms
    static int cSrcColor, cSrcDepth, cSrcRect, cSrcSize, cDstSize, cProjAB, cInvVP, cBodyA, cBodyB, cMinDist;
    // display uniforms
    static int dViewProj, dColor, dDepth, dMode, dQLin, dQOff, dCapVP, dCapRect, dTexScale, dTexelHalf, dCapAB, dClip,
            dOriginRel, dAxisW, dAxisH, dHalfSize, dTint, dTime, dAlpha, dMix, dDepthBias, dDebug, dDispLod;

    /** Triangle vertices (x,y,z camera relative), grown on demand, never freed (a few KB). */
    static FloatBuffer mesh = MemoryUtil.memAllocFloat(3 * 3 * 64);

    private PostcardGl() {}

    /** Lazily compiles everything. Returns false (and stays false) if anything failed. */
    static boolean ensure() {
        if (initDone) return !failed;
        initDone = true;
        try {
            captureProgram = link("postcard_capture");
            displayProgram = link("postcard_display");
            cSrcColor = glGetUniformLocation(captureProgram, "uSrcColor");
            cSrcDepth = glGetUniformLocation(captureProgram, "uSrcDepth");
            cSrcRect = glGetUniformLocation(captureProgram, "uSrcRect");
            cSrcSize = glGetUniformLocation(captureProgram, "uSrcSize");
            cDstSize = glGetUniformLocation(captureProgram, "uDstSize");
            cProjAB = glGetUniformLocation(captureProgram, "uProjAB");
            cInvVP = glGetUniformLocation(captureProgram, "uInvVP");
            cBodyA = glGetUniformLocation(captureProgram, "uBodyA");
            cBodyB = glGetUniformLocation(captureProgram, "uBodyB");
            cMinDist = glGetUniformLocation(captureProgram, "uMinDist");
            int p = displayProgram;
            dViewProj = glGetUniformLocation(p, "uViewProj");
            dColor = glGetUniformLocation(p, "uColor");
            dDepth = glGetUniformLocation(p, "uDepth");
            dMode = glGetUniformLocation(p, "uMode");
            dQLin = glGetUniformLocation(p, "uQLin");
            dQOff = glGetUniformLocation(p, "uQOff");
            dCapVP = glGetUniformLocation(p, "uCapVP");
            dCapRect = glGetUniformLocation(p, "uCapRect");
            dTexScale = glGetUniformLocation(p, "uTexScale");
            dTexelHalf = glGetUniformLocation(p, "uTexelHalf");
            dCapAB = glGetUniformLocation(p, "uCapAB");
            dClip = glGetUniformLocation(p, "uClip");
            dOriginRel = glGetUniformLocation(p, "uOriginRel");
            dAxisW = glGetUniformLocation(p, "uAxisW");
            dAxisH = glGetUniformLocation(p, "uAxisH");
            dHalfSize = glGetUniformLocation(p, "uHalfSize");
            dTint = glGetUniformLocation(p, "uTint");
            dTime = glGetUniformLocation(p, "uTime");
            dAlpha = glGetUniformLocation(p, "uAlpha");
            dMix = glGetUniformLocation(p, "uMix");
            dDepthBias = glGetUniformLocation(p, "uDepthBias");
            dDebug = glGetUniformLocation(p, "uDebug");
            dDispLod = glGetUniformLocation(p, "uDispLod");

            emptyVao = glGenVertexArrays();
            meshVao = glGenVertexArrays();
            meshVbo = glGenBuffers();
            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int prevBuf = glGetInteger(GL_ARRAY_BUFFER_BINDING);
            glBindVertexArray(meshVao);
            glBindBuffer(GL_ARRAY_BUFFER, meshVbo);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 12, 0L);
            glBindVertexArray(prevVao);
            glBindBuffer(GL_ARRAY_BUFFER, prevBuf);

            linearSampler = sampler(GL_LINEAR);
            nearestSampler = sampler(GL_NEAREST);
            mipSampler = sampler(GL_LINEAR);
            glSamplerParameteri(mipSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            FlyAwayPerf.LOGGER.info("[postcards] shaders compiled (GL {})", glGetString(GL_VERSION));
        } catch (Throwable t) {
            failed = true;
            FlyAwayPerf.LOGGER.error("[postcards] disabled: could not set up GL objects", t);
        }
        return !failed;
    }

    static boolean isFailed() {
        return failed;
    }

    static void disable(String why, Throwable t) {
        failed = true;
        initDone = true;
        FlyAwayPerf.LOGGER.error("[postcards] disabled after an error while {}", why, t);
    }

    private static int sampler(int filter) {
        int s = glGenSamplers();
        glSamplerParameteri(s, GL_TEXTURE_MIN_FILTER, filter);
        glSamplerParameteri(s, GL_TEXTURE_MAG_FILTER, filter);
        glSamplerParameteri(s, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(s, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glSamplerParameteri(s, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        return s;
    }

    private static String source(String name) throws IOException {
        String path = "/assets/flyaway_perf/shaders/" + name;
        try (InputStream in = PostcardGl.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int compile(int type, String name) throws IOException {
        int s = glCreateShader(type);
        glShaderSource(s, source(name));
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(s);
            glDeleteShader(s);
            throw new IllegalStateException(name + ": " + log);
        }
        return s;
    }

    private static int link(String base) throws IOException {
        int vs = compile(GL_VERTEX_SHADER, base + ".vsh");
        int fs = compile(GL_FRAGMENT_SHADER, base + ".fsh");
        int p = glCreateProgram();
        glAttachShader(p, vs);
        glAttachShader(p, fs);
        glLinkProgram(p);
        glDetachShader(p, vs);
        glDetachShader(p, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(p);
            glDeleteProgram(p);
            throw new IllegalStateException(base + ": " + log);
        }
        return p;
    }

    /**
     * Allocates the textures + FBO of an entry: res x res, RGBA8 premultiplied colour (alpha = valid) and RG16F
     * disparity (r = 1/distance * valid, g = valid), both with a full mip chain (filled by {@link #generateMips}).
     * Caller saved GL state.
     */
    static void allocate(PostcardCache.Entry e, int res) {
        e.releaseGl();
        e.res = res;
        // A bound pixel-unpack buffer would turn the null data pointer into an offset into that buffer.
        int pbo = glGetInteger(GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (pbo != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        e.colorTex = texture(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE, res);
        e.depthTex = texture(GL_RG16F, GL_RG, GL_HALF_FLOAT, res);
        if (pbo != 0) glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
        e.fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, e.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, e.colorTex, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, e.depthTex, 0);
        try (MemoryStack st = MemoryStack.stackPush()) {
            glDrawBuffers(st.ints(GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1));
        }
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            e.releaseGl();
            throw new IllegalStateException("postcard FBO incomplete: 0x" + Integer.toHexString(status));
        }
    }

    private static int texture(int internal, int format, int type, int res) {
        int t = glGenTextures();
        glActiveTexture(GL_TEXTURE0 + GlStateSnapshot.UNIT_A);
        glBindTexture(GL_TEXTURE_2D, t);
        int levels = mipLevels(res);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAX_LEVEL, levels - 1);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        for (int l = 0, sz = res; l < levels; l++, sz = Math.max(1, sz >> 1)) {
            glTexImage2D(GL_TEXTURE_2D, l, internal, sz, sz, 0, format, type, (java.nio.ByteBuffer) null);
        }
        return t;
    }

    static int mipLevels(int res) {
        return 32 - Integer.numberOfLeadingZeros(res);
    }

    /** Rebuilds the mip chains after a capture (they drive the smooth depth proxy and the blurred fill). */
    static void generateMips(PostcardCache.Entry e) {
        glActiveTexture(GL_TEXTURE0 + GlStateSnapshot.UNIT_A);
        glBindTexture(GL_TEXTURE_2D, e.colorTex);
        glGenerateMipmap(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, e.depthTex);
        glGenerateMipmap(GL_TEXTURE_2D);
    }

    static void ensureMeshCapacity(int floats) {
        if (mesh.capacity() < floats) {
            FloatBuffer n = MemoryUtil.memAllocFloat(Math.max(floats, mesh.capacity() * 2));
            MemoryUtil.memFree(mesh);
            mesh = n;
        }
    }

    static void uniformMat4(int loc, Matrix4f m) {
        if (loc < 0) return;
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(16);
            m.get(fb);
            glUniformMatrix4fv(loc, false, fb);
        }
    }

    static void uniformMat3(int loc, Matrix3f m) {
        if (loc < 0) return;
        try (MemoryStack st = MemoryStack.stackPush()) {
            FloatBuffer fb = st.mallocFloat(9);
            m.get(fb);
            glUniformMatrix3fv(loc, false, fb);
        }
    }
}
