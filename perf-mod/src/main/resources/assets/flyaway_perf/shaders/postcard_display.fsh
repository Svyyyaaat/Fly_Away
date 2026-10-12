#version 330 core
// Draws a nested portal's surface from its postcard.
// Per fragment, the view ray (current camera -> point on Q) is carried through Q's portal transform into the
// destination space of the capture and marched against a SMOOTH depth proxy: a coarse mip level of the captured
// disparity (1 / distance). A smooth proxy cannot tear: content shifts with soft parallax instead of shearing apart
// at depth edges, which is what the full-resolution depth did when the viewpoint differed from the capture's.
// Rays that leave the captured area sample an increasingly blurred copy of the nearest captured edge; only portals
// that were never captured show the plain animated membrane.
in vec3 vRel;

uniform sampler2D uColor;     // postcard colour, premultiplied, alpha = valid (mipmapped)
uniform sampler2D uDepth;     // postcard disparity: r = 1/distance * valid, g = valid (mipmapped)
uniform float uDispLod;       // mip level of the depth proxy
uniform int   uMode;          // 0 = postcard, 1 = membrane only (no capture yet)
uniform mat3  uQLin;          // linear part of Q's transform (rotation, scale, mirror)
uniform vec3  uQOff;          // Q.transformPoint(currentCamera) - captureCamera
uniform mat4  uCapVP;         // capture projection * view (relative to the capture camera)
uniform vec4  uCapRect;       // captured NDC rectangle: xy = min, zw = max
uniform vec2  uTexScale;      // used fraction of the postcard texture
uniform vec2  uTexelHalf;     // half a texel, in texture UV
uniform vec2  uCapAB;         // capture projection m22, m32 (depth linearisation)
uniform vec4  uClip;          // outer portal clip plane (camera relative): keep dot(xyz, vRel) + w >= 0
uniform vec3  uOriginRel;     // Q origin relative to the camera
uniform vec3  uAxisW;
uniform vec3  uAxisH;
uniform vec2  uHalfSize;      // Q half width / half height
uniform vec3  uTint;          // destination sky/fog tint (membrane)
uniform float uTime;
uniform float uAlpha;         // overall opacity (fade postcard -> live view)
uniform float uMix;           // membrane -> postcard fade-in
uniform float uDepthBias;
uniform int   uDebug;

layout(location = 0) out vec4 oColor;

bool toUv(vec4 c, out vec2 r) {
    r = vec2(-1.0);
    if (c.w <= 1.0e-4) return false;
    vec2 ndc = c.xy / c.w;
    r = (ndc - uCapRect.xy) / (uCapRect.zw - uCapRect.xy);
    return all(greaterThanEqual(r, vec2(0.0))) && all(lessThanEqual(r, vec2(1.0)));
}

vec2 texUv(vec2 r) {
    return clamp(r * uTexScale, uTexelHalf, uTexScale - uTexelHalf);
}

// Disparity of the smooth proxy at r (0 = sky). Holes (hand, own body) are filled from coarser levels.
float dispAt(vec2 r) {
    vec2 tu = texUv(r);
    vec2 d = textureLod(uDepth, tu, uDispLod).rg;
    if (d.y < 0.3) d = textureLod(uDepth, tu, uDispLod + 2.0).rg;
    return d.y > 1.0e-3 ? d.x / d.y : 0.0;
}

// Captured colour at r, blurred by lod; invalid texels are filled from coarser levels (push-pull).
vec3 colorAt(vec2 r, float lod) {
    vec2 tu = texUv(r);
    vec4 a = textureLod(uColor, tu, lod);
    if (a.a < 0.98) {
        vec4 b = textureLod(uColor, tu, lod + 3.0);
        if (b.a < 0.3) b = textureLod(uColor, tu, lod + 6.0);
        return a.rgb + (1.0 - a.a) * b.rgb / max(b.a, 1.0e-3);
    }
    return a.rgb / a.a;
}

vec3 membrane(vec2 l) {
    float t = uTime;
    float r = length(l);
    float n = sin(l.x * 2.7 + t * 0.35) * sin(l.y * 2.2 - t * 0.28)
            + 0.5 * sin((l.x - l.y) * 4.1 + t * 0.55)
            + 0.25 * sin(r * 9.0 - t * 0.9);
    vec3 c = mix(uTint * 0.78, uTint * 1.08, 0.5 + 0.5 * l.y);
    c *= 0.94 + 0.06 * n;
    c += vec3(0.30, 0.16, 0.48) * 0.10 * (0.5 + 0.5 * sin(atan(l.y, l.x) * 2.0 + r * 5.0 - t * 0.6)) * (1.0 - 0.6 * r);
    return clamp(c, 0.0, 1.0);
}

// Blurred continuation outside the captured rectangle; w = confidence.
vec4 outside(vec4 c, float baseConf) {
    if (c.w <= 1.0e-4) return vec4(0.0);
    vec2 re = (c.xy / c.w - uCapRect.xy) / (uCapRect.zw - uCapRect.xy);
    vec2 oe = max(-re, re - 1.0);
    float o = max(max(oe.x, oe.y), 0.0);
    vec3 col = colorAt(clamp(re, 0.0, 1.0), 1.5 + 6.0 * o);
    return vec4(col, baseConf * (1.0 - 0.8 * smoothstep(0.0, 0.6, o)));
}

// Reprojected colour; w = confidence (0 = no data).
vec4 reproject() {
    vec3 p0 = uQLin * vRel + uQOff;   // the fragment's point on Q's destination plane, capture-camera relative
    vec3 dir = normalize(uQLin * vRel);
    float tPrev = 0.0;
    float t = 0.0;
    vec2 r;
    bool hit = false;
    bool left = false;
    bool anyIn = false;
    vec2 lastIn = vec2(0.5);
    for (int i = 0; i < 20; i++) {
        t = 0.05 * pow(1.6, float(i));
        vec4 c = uCapVP * vec4(p0 + dir * t, 1.0);
        // Still behind the capture camera (a capture from the player's own view, taken just in front of Q's
        // destination): that stretch of the ray was never seen; carry on.
        if (c.w < 0.05) { tPrev = t; continue; }
        if (!toUv(c, r)) {
            // Outside the captured area. Before the ray has entered it, it may still enter further on (it starts
            // next to the capture camera, where the frustum is narrow); after that, it has left.
            if (anyIn) { left = true; break; }
            tPrev = t;
            continue;
        }
        anyIn = true;
        lastIn = r;
        if (1.0 / c.w <= dispAt(r)) { hit = true; break; }
        tPrev = t;
    }
    if (hit) {
        float lo = tPrev;
        float hi = t;
        for (int j = 0; j < 6; j++) {
            float m = 0.5 * (lo + hi);
            vec4 cm = uCapVP * vec4(p0 + dir * m, 1.0);
            vec2 rm;
            if (toUv(cm, rm) && 1.0 / cm.w <= dispAt(rm)) { hi = m; } else { lo = m; }
        }
        vec4 c = uCapVP * vec4(p0 + dir * hi, 1.0);
        if (!toUv(c, r)) r = lastIn;
        return vec4(colorAt(r, 0.0), 1.0);
    }
    if (!left) {
        // Nothing along the ray inside the march distance: sky. Use the point at infinity in the ray direction.
        vec4 ci = uCapVP * vec4(dir, 0.0);
        vec2 ri;
        if (toUv(ci, ri)) return vec4(colorAt(ri, 0.0), 1.0);
        return outside(ci, 0.9);
    }
    if (anyIn) {
        // The ray left the captured area before hitting anything. Assume the surface continues at the depth last
        // seen and look up where the ray reaches that depth, blurred more the further outside the capture it is.
        float dl = dispAt(lastIn);
        if (dl <= 1.0e-5) return outside(uCapVP * vec4(dir, 0.0), 0.9);
        float w0 = (uCapVP * vec4(p0, 1.0)).w;
        float wd = (uCapVP * vec4(dir, 0.0)).w;
        float tEst = wd > 1.0e-4 ? max((1.0 / dl - w0) / wd, 0.0) : t;
        return outside(uCapVP * vec4(p0 + dir * tEst, 1.0), 0.9);
    }
    // The ray never entered the captured area: nearest edge in its direction, blurred.
    vec4 c0 = uCapVP * vec4(p0, 1.0);
    return outside(c0.w > 0.05 ? c0 : uCapVP * vec4(dir, 0.0), 0.75);
}

void main() {
    if (dot(uClip.xyz, vRel) + uClip.w < 0.0) discard;
    vec3 d = vRel - uOriginRel;
    vec2 l = vec2(dot(d, uAxisW) / uHalfSize.x, dot(d, uAxisH) / uHalfSize.y);
    vec3 col = membrane(l);
    if (uMode == 0) {
        vec4 pc = reproject();
        col = mix(col, pc.rgb, clamp(pc.w * uMix, 0.0, 1.0));
    }
    // Subtle edge vignette so the surface reads as a portal.
    float e = max(abs(l.x), abs(l.y));
    float v = smoothstep(0.80, 1.02, e);
    col = mix(col, col * 0.7 + uTint * 0.12, v * 0.45);
    if (uDebug != 0) col = mix(col, vec3(1.0, 0.0, 1.0), 0.45);
    oColor = vec4(col, uAlpha);
    gl_FragDepth = max(gl_FragCoord.z - uDepthBias, 0.0);
}
