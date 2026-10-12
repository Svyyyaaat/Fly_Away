#version 330 core
// Copies a screen rectangle of the main render target (a finished layer-1 portal pass, or the player's own view)
// into a postcard, stretched over the whole texture:
//  * colour: 4-tap box downsample, premultiplied, alpha = valid;
//  * disparity: 1 / view distance (0 = sky), times valid, with valid in g.
// Pixels that must not end up in the postcard (the held item / hand, the player's own body in third person) are
// written as invalid; the display shader fills them from the mip chain.
uniform sampler2D uSrcColor;   // main target colour (linear sampler)
uniform sampler2D uSrcDepth;   // main target depth  (nearest sampler)
uniform vec4 uSrcRect;         // xy = rect origin, zw = rect size, in main-target UV
uniform vec2 uSrcSize;         // main target size in pixels
uniform vec2 uDstSize;         // postcard size in pixels
uniform vec2 uProjAB;          // capture projection m22, m32 (depth linearisation)
uniform mat4 uInvVP;           // inverse capture projection * view (camera relative)
uniform vec3 uBodyA;           // player body segment (feet, head) relative to the camera; uBodyA == uBodyB: none
uniform vec3 uBodyB;
uniform float uMinDist;        // anything closer than this is the hand / held item

layout(location = 0) out vec4 oColor;
layout(location = 1) out vec4 oDisp;

void main() {
    vec2 f = gl_FragCoord.xy / uDstSize;
    vec2 uv = uSrcRect.xy + f * uSrcRect.zw;
    vec2 q = uSrcRect.zw / uDstSize * 0.25;
    vec3 c = texture(uSrcColor, uv + vec2(-q.x, -q.y)).rgb
           + texture(uSrcColor, uv + vec2( q.x, -q.y)).rgb
           + texture(uSrcColor, uv + vec2(-q.x,  q.y)).rgb
           + texture(uSrcColor, uv + vec2( q.x,  q.y)).rgb;
    ivec2 p = clamp(ivec2(uv * uSrcSize), ivec2(0), ivec2(uSrcSize) - 1);
    float d = texelFetch(uSrcDepth, p, 0).r;
    float valid = 1.0;
    float disp = 0.0;
    if (d < 0.999999) {
        float z = d * 2.0 - 1.0;
        float dist = uProjAB.y / (z + uProjAB.x);
        if (dist < uMinDist) valid = 0.0;
        if (uBodyA != uBodyB) {
            vec4 w = uInvVP * vec4(uv * 2.0 - 1.0, z, 1.0);
            vec3 pos = w.xyz / w.w;
            vec3 ab = uBodyB - uBodyA;
            float k = clamp(dot(pos - uBodyA, ab) / dot(ab, ab), 0.0, 1.0);
            if (length(pos - (uBodyA + ab * k)) < 0.85) valid = 0.0;
        }
        disp = 1.0 / max(dist, 1.0e-3);
    }
    oColor = vec4(c * 0.25 * valid, valid);
    oDisp = vec4(disp * valid, valid, 0.0, 1.0);
}
