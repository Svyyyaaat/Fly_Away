#version 330 core
// Draws a nested portal's surface from its postcard.
// Per fragment, the view ray (current camera -> point on Q) is carried through Q's portal transform into the
// destination space of the capture and marched against the stored depth in the capture's clip space (screen-space
// ray march, like TAA reprojection with parallax). The first crossing gives the colour. Rays that leave the captured
// area fade into a swirl, and so does the whole surface when there is no capture yet.
in vec3 vRel;

uniform sampler2D uColor;     // postcard colour (linear sampler)
uniform sampler2D uDepth;     // postcard raw depth (nearest sampler)
uniform int   uMode;          // 0 = postcard, 1 = swirl only (no capture yet)
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
uniform vec3  uTint;          // destination sky/fog tint (swirl)
uniform float uTime;
uniform float uAlpha;         // overall opacity (fade postcard -> live view)
uniform float uMix;           // swirl -> postcard fade-in
uniform float uDepthBias;
uniform int   uDebug;

layout(location = 0) out vec4 oColor;

const float SKY = 1.0e9;

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

float sceneDist(vec2 r) {
    float d = texture(uDepth, texUv(r)).r;
    if (d >= 0.999999) return SKY;
    return uCapAB.y / ((d * 2.0 - 1.0) + uCapAB.x);
}

vec3 swirl(vec2 l) {
    float r = length(l);
    float a = atan(l.y, l.x);
    float s1 = sin(a * 3.0 + r * 7.0 - uTime * 1.6);
    float s2 = sin(a * -2.0 + r * 11.0 + uTime * 1.1);
    float n = sin(l.x * 6.3 + uTime * 0.7) * sin(l.y * 5.1 - uTime * 0.9);
    float k = 0.55 + 0.25 * s1 + 0.12 * s2 + 0.08 * n;
    vec3 c = uTint * (0.65 + 0.5 * k);
    c += vec3(0.35, 0.18, 0.55) * 0.25 * (0.5 + 0.5 * s1) * (1.0 - r * 0.5);
    return clamp(c, 0.0, 1.0);
}

// Reprojected colour; w = confidence (0 = no data).
vec4 reproject() {
    vec3 p0 = uQLin * vRel + uQOff;
    vec3 dir = normalize(uQLin * vRel);
    float tPrev = 0.0;
    float t = 0.0;
    vec2 r;
    bool hit = false;
    bool outside = false;
    bool anyIn = false;
    vec2 lastIn = vec2(0.5);
    for (int i = 0; i < 24; i++) {
        t = 0.04 * pow(1.52, float(i));
        vec4 c = uCapVP * vec4(p0 + dir * t, 1.0);
        if (!toUv(c, r)) { outside = true; break; }
        anyIn = true;
        lastIn = r;
        if (c.w >= sceneDist(r)) { hit = true; break; }
        tPrev = t;
    }
    if (hit) {
        float lo = tPrev;
        float hi = t;
        for (int j = 0; j < 6; j++) {
            float m = 0.5 * (lo + hi);
            vec4 cm = uCapVP * vec4(p0 + dir * m, 1.0);
            vec2 rm;
            if (toUv(cm, rm) && cm.w >= sceneDist(rm)) { hi = m; } else { lo = m; }
        }
        vec4 c = uCapVP * vec4(p0 + dir * hi, 1.0);
        if (!toUv(c, r)) r = lastIn;
        float sd = sceneDist(r);
        vec2 tu = texUv(r);
        if (sd < SKY && c.w - sd > 0.6 + 0.08 * sd) {
            // The ray went behind an occluder (disocclusion): fill with a small blur of the occluder edge.
            vec2 o = uTexelHalf * 3.0;
            vec3 b = texture(uColor, tu + vec2(o.x, o.y)).rgb + texture(uColor, tu + vec2(-o.x, o.y)).rgb
                   + texture(uColor, tu + vec2(o.x, -o.y)).rgb + texture(uColor, tu + vec2(-o.x, -o.y)).rgb;
            return vec4(b * 0.25, 0.9);
        }
        return vec4(texture(uColor, tu).rgb, 1.0);
    }
    if (!outside) {
        // Nothing along the ray inside the march distance: sky. Use the point at infinity in the ray direction.
        vec2 ri;
        if (toUv(uCapVP * vec4(dir, 0.0), ri)) return vec4(texture(uColor, texUv(ri)).rgb, 1.0);
        return vec4(texture(uColor, texUv(lastIn)).rgb, 0.5);
    }
    if (anyIn) {
        // The ray left the captured area before hitting anything. Assume the surface continues at the depth last
        // seen and look up where the ray reaches that depth; the further outside the capture that is, the more the
        // swirl takes over. Continuous across pixels, so no banding from the march step that exited.
        float dEst = sceneDist(lastIn);
        vec4 ce;
        if (dEst >= SKY) {
            ce = uCapVP * vec4(dir, 0.0);
        } else {
            float w0 = (uCapVP * vec4(p0, 1.0)).w;
            float wd = (uCapVP * vec4(dir, 0.0)).w;
            float tEst = wd > 1.0e-4 ? max((dEst - w0) / wd, 0.0) : t;
            ce = uCapVP * vec4(p0 + dir * tEst, 1.0);
        }
        if (ce.w > 1.0e-4) {
            vec2 re = (ce.xy / ce.w - uCapRect.xy) / (uCapRect.zw - uCapRect.xy);
            vec2 oe = max(-re, re - 1.0);
            float conf = 0.85 * (1.0 - smoothstep(0.0, 0.1, max(oe.x, oe.y)));
            return vec4(texture(uColor, texUv(clamp(re, 0.0, 1.0))).rgb, conf);
        }
        return vec4(texture(uColor, texUv(lastIn)).rgb, 0.2);
    }
    // The ray leaves the captured area at once: nearest edge, low confidence.
    vec4 c0 = uCapVP * vec4(p0, 1.0);
    if (c0.w > 1.0e-4) {
        vec2 ndc = c0.xy / c0.w;
        vec2 r0 = (ndc - uCapRect.xy) / (uCapRect.zw - uCapRect.xy);
        vec2 o0 = max(-r0, r0 - 1.0);
        float c0Conf = 0.7 * (1.0 - smoothstep(0.0, 0.08, max(o0.x, o0.y)));
        return vec4(texture(uColor, texUv(clamp(r0, 0.0, 1.0))).rgb, c0Conf);
    }
    return vec4(0.0);
}

void main() {
    if (dot(uClip.xyz, vRel) + uClip.w < 0.0) discard;
    vec3 d = vRel - uOriginRel;
    vec2 l = vec2(dot(d, uAxisW) / uHalfSize.x, dot(d, uAxisH) / uHalfSize.y);
    vec3 col = swirl(l);
    if (uMode == 0) {
        vec4 pc = reproject();
        col = mix(col, pc.rgb, clamp(pc.w * uMix, 0.0, 1.0));
    }
    // Subtle edge vignette so the surface reads as a portal.
    float e = max(abs(l.x), abs(l.y));
    float v = smoothstep(0.72, 1.02, e);
    col = mix(col, col * 0.55 + uTint * 0.18, v * 0.6);
    if (uDebug != 0) col = mix(col, vec3(1.0, 0.0, 1.0), 0.45);
    oColor = vec4(col, uAlpha);
    gl_FragDepth = max(gl_FragCoord.z - uDepthBias, 0.0);
}
