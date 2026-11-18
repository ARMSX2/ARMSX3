// SPDX-License-Identifier: GPL-2.0-only
// Valley's terrain, depth of field and feedback, translated from the GLSL in Android's XmbValley.kt (the firmware's canyon programs).

#include <metal_stdlib>
using namespace metal;

struct XmbValleyTerrainUniforms {
    float4x4 uMvp;
    float3 uEye;
    float4 uLine;
    float3 uTime;
    float uTexScale;
    float uFogMin;
    float uFogRange;
    float3 uScale;
};

struct XmbValleyColourUniforms {
    float3 uFogColour;
    float3 uLineColour;
    float uBumpScale;
};

struct XmbValleyFocusUniforms {
    float uFocalDepth;
    float uFocus;
};

struct XmbValleyLayerUniforms {
    float uLod;
    float uFeedback;
    float2 uResRecip;
    float3 uNoiseUV;
    float2 uFbScale;
    float2 uFbRot;
};

struct XmbValleyTerrainVarying {
    float4 position [[position]];
    float2 vTc0;
    float2 vTc1;
    float2 vParams;
    float3 vU;
    float3 vV;
    float3 vN;
    float3 vEye;
    float vClip;
    float vDist;
};

struct XmbValleyQuadVarying {
    float4 position [[position]];
    float2 vTc;
};

static float3 xmbValley_frame(float3 n) {
    return n.z < 0.99 ? normalize(cross(n, float3(0.0, 0.0, 1.0))) : normalize(cross(n, float3(0.0, 1.0, 0.0)));
}

static float xmbValley_falloff(float dz, float4 uLine) {
    float d = clamp(abs(dz) / uLine.x, 0.0, 1.0);
    return 1.0 - d * d * (3.0 - 2.0 * d);
}

// A picture drawn into a Metal target has its first row at the top, where GL's has it at the bottom.
static float2 xmbValley_target(float2 uv) {
    return float2(uv.x, 1.0 - uv.y);
}

// canyon.vpo: the height lifted by TERRAIN HEIGHT and rolled by the line's wave, new rows risen from their own heights
// until TIME AHEAD passes them, the normal taken again from the lifted ground, fog by distance. A point is 11 floats:
// position and the row's own height, normal, texture coordinates with the rise's time and height.
vertex XmbValleyTerrainVarying xmbValley_terrainVertex(uint vid [[vertex_id]],
                                                       const device float *points [[buffer(0)]],
                                                       constant XmbValleyTerrainUniforms &u [[buffer(1)]]) {
    const device float *v = points + vid * 11;
    float4 aPos = float4(v[0], v[1], v[2], v[3]);
    float3 aNrm = float3(v[4], v[5], v[6]);
    float4 aTc = float4(v[7], v[8], v[9], v[10]);
    XmbValleyTerrainVarying out;
    out.vTc0 = aTc.xy;
    out.vTc1 = aTc.xy * u.uTexScale;
    out.vClip = aPos.z - u.uTime.z;
    float3 n = normalize(aNrm);
    float3 t = xmbValley_frame(n);
    float3 b = cross(n, t);
    float rise = clamp((aTc.z - u.uTime.y) * 0.01, 0.0, 9999.0);
    float3 p = float3(aPos.x * u.uScale.x, aPos.y + aTc.w * rise * rise, aPos.z);
    float fP = xmbValley_falloff(p.z - u.uTime.x, u.uLine);
    float3 pt = p + t;
    float3 pb = p + b;
    float cT = cos(abs(u.uTime.x - pt.z) * u.uLine.z);
    float cB = cos(abs(u.uTime.x - pb.z) * u.uLine.z);
    float yP = p.y * (u.uLine.y * cT * (u.uLine.w + fP) + u.uScale.y);
    float yT = pt.y * (u.uLine.y * cT * (u.uLine.w + xmbValley_falloff(u.uTime.x - pt.z, u.uLine)) + u.uScale.y);
    float yB = pb.y * (u.uLine.y * cB * (u.uLine.w + xmbValley_falloff(u.uTime.x - pb.z, u.uLine)) + u.uScale.y);
    float y = yP + 0.2 * fP * (aPos.w * u.uScale.y - yP);
    float3 q = float3(p.x, yP, p.z);
    float3 dt = float3(pt.x, yT, pt.z) - q;
    float3 db = float3(pb.x, yB, pb.z) - q;
    float3 world = float3(p.x, y, p.z);
    out.position = u.uMvp * float4(world, 1.0);
    float3 toEye = u.uEye - world;
    float dist = length(toEye);
    out.vDist = dist;
    out.vEye = toEye / dist;
    float3 nn = normalize(cross(db, dt));
    out.vParams = float2(1.0 - clamp((dist - u.uFogMin) / u.uFogRange, 0.0, 1.0), 0.2 * fP * abs(y));
    out.vN = nn;
    out.vU = xmbValley_frame(nn);
    out.vV = cross(nn, out.vU);
    return out;
}

// canyon.fpo: the normal map tips the normal, the colour comes from how squarely the point faces the camera, lit toward
// the line's colour near the line, fogged with distance and toward the sides.
fragment float4 xmbValley_terrainFragment(XmbValleyTerrainVarying in [[stage_in]],
                                          texture2d<float> uNormalMap [[texture(0)]],
                                          texture2d<float> uFresLut [[texture(1)]],
                                          constant XmbValleyColourUniforms &u [[buffer(0)]]) {
    constexpr sampler normals(filter::linear, mip_filter::linear, address::repeat);
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    if (in.vClip > 0.0) discard_fragment();
    float3 m = uNormalMap.sample(normals, in.vTc1).xyz - 0.5;
    float3 nb = in.vU * (m.x * u.uBumpScale) + in.vV * (m.y * u.uBumpScale) + in.vN * m.z;
    float facing = abs(dot(in.vEye, nb) * rsqrt(max(dot(nb, nb), 1e-12)));
    float3 c = uFresLut.sample(nearest, float2(facing, 0.5)).rgb;
    c += in.vParams.y * (u.uLineColour - c);
    c = in.vParams.x * (c - u.uFogColour) + u.uFogColour;
    float side = abs(in.vTc0.x - 0.5);
    c += clamp(side * side * side, 0.0, 1.0) * (u.uFogColour - c);
    return float4(c, 1.0);
}

// canyon_blur_ammount.fpo: how far from the focus, the distance, and which side of it.
fragment float4 xmbValley_focusFragment(XmbValleyTerrainVarying in [[stage_in]],
                                        constant XmbValleyFocusUniforms &u [[buffer(0)]]) {
    if (in.vClip > 0.0) discard_fragment();
    return float4(clamp(abs(in.vDist - u.uFocalDepth) * u.uFocus, 0.0, 1.0), in.vDist * 0.01, in.vDist > u.uFocalDepth ? 1.0 : 0.0, 0.0);
}

vertex XmbValleyQuadVarying xmbValley_quadVertex(uint vid [[vertex_id]]) {
    float2 aPos = float2((vid & 1) != 0 ? 1.0 : -1.0, (vid & 2) != 0 ? 1.0 : -1.0);
    XmbValleyQuadVarying out;
    out.vTc = aPos * 0.5 + 0.5;
    out.position = float4(aPos, 0.0, 1.0);
    return out;
}

// canyon_layer_blur.fpo: the scene read from a coarser level and jittered as far as each point is out of focus, laid
// over the last frame drawn back in, scaled and turned about the middle (FEEDBACK, X, Y, ROT), with the firmware's
// dither. Window positions are GL's, from the bottom left.
fragment float4 xmbValley_layerFragment(XmbValleyQuadVarying in [[stage_in]],
                                        texture2d<float> uCoc [[texture(0)]],
                                        texture2d<float> uScreen [[texture(1)]],
                                        texture2d<float> uNoise1 [[texture(2)]],
                                        texture2d<float> uFront [[texture(3)]],
                                        texture2d<float> uNoise2 [[texture(4)]],
                                        constant XmbValleyLayerUniforms &u [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    constexpr sampler levels(filter::linear, mip_filter::linear, address::clamp_to_edge);
    constexpr sampler noise(filter::nearest, address::repeat);
    float2 wpos = float2(in.position.x, 1.0 / u.uResRecip.y - in.position.y);
    float blur = clamp(uCoc.sample(linear, xmbValley_target(in.vTc)).x * 0.75 + u.uLod, 0.0, 1.0);
    float3 n1 = uNoise1.sample(noise, wpos / 64.0).xyz - 0.5;
    // Called level in the GLSL; Metal has a level() of its own.
    float mip = blur * 6.0;
    float w = clamp(mip, 0.0, 1.0);
    float2 uv = wpos * u.uResRecip + mip * n1.xy * float2(0.0004, 0.000225);
    float3 front = uFront.sample(levels, xmbValley_target(uv), level(clamp(mip - 2.0, 0.0, 1.0) + 0.1)).rgb;
    float wF = w * u.uFeedback;
    float3 sum = (w - wF) * (n1 * (1.0 / 128.0) + front);
    float total = w + u.uFeedback - wF;
    float2 c = (in.vTc - 0.5) * u.uFbScale;
    float2 r = float2(dot(c, u.uFbRot.yx), dot(float2(-u.uFbRot.x, u.uFbRot.y), c)) + 0.5;
    float3 prev = uScreen.sample(linear, xmbValley_target(r)).rgb;
    float3 col = total > 0.0 ? (prev * u.uFeedback + sum) / total : front;
    float4 n2 = uNoise2.sample(noise, wpos * u.uNoiseUV.z + u.uNoiseUV.xy);
    return float4(col, 1.0) + float4(n2.rgb, 0.0) * (1.0 / 64.0);
}

// canyon_layer_blur_blur.fpo: four samples half a point either way, averaged.
fragment float4 xmbValley_outFragment(XmbValleyQuadVarying in [[stage_in]],
                                      texture2d<float> uTex [[texture(0)]],
                                      constant float2 &uStep [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float2 h = uStep * 0.5;
    float2 tc = xmbValley_target(in.vTc);
    float4 c = uTex.sample(linear, tc + float2(h.x, -h.y)) + uTex.sample(linear, tc - h)
        + uTex.sample(linear, tc + h) + uTex.sample(linear, tc + float2(-h.x, h.y));
    return float4(c.rgb * 0.25, 1.0);
}
