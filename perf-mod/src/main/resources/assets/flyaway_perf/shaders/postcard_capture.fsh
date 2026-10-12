#version 330 core
// Copies the portal's screen rectangle of the main render target (the finished image of a layer-1 portal pass)
// into a small postcard: colour (4-tap box downsample) and raw window-space depth (nearest sample).
uniform sampler2D uSrcColor;   // main target colour (linear sampler)
uniform sampler2D uSrcDepth;   // main target depth  (nearest sampler)
uniform vec4 uSrcRect;         // xy = rect origin, zw = rect size, in main-target UV
uniform vec2 uSrcSize;         // main target size in pixels
uniform vec2 uDstSize;         // used postcard area in pixels

layout(location = 0) out vec4 oColor;
layout(location = 1) out vec4 oDepth;

void main() {
    vec2 f = gl_FragCoord.xy / uDstSize;
    vec2 uv = uSrcRect.xy + f * uSrcRect.zw;
    vec2 q = uSrcRect.zw / uDstSize * 0.25;
    vec3 c = texture(uSrcColor, uv + vec2(-q.x, -q.y)).rgb
           + texture(uSrcColor, uv + vec2( q.x, -q.y)).rgb
           + texture(uSrcColor, uv + vec2(-q.x,  q.y)).rgb
           + texture(uSrcColor, uv + vec2( q.x,  q.y)).rgb;
    oColor = vec4(c * 0.25, 1.0);
    ivec2 p = clamp(ivec2(uv * uSrcSize), ivec2(0), ivec2(uSrcSize) - 1);
    oDepth = vec4(texelFetch(uSrcDepth, p, 0).r, 0.0, 0.0, 1.0);
}
