// SPDX-License-Identifier: GPL-2.0-only
// The Waves visualizer's picture of the wave in a lines.qrc mood, its sky, exposure and glare, translated from the GLSL in Android's XmbFirmwareWave.kt.

#include <metal_stdlib>
using namespace metal;

struct XmbMoodQuadVarying {
    float4 position [[position]];
    float2 vUv;
};

struct XmbMoodSceneUniforms {
    float3 uCol1;
    float3 uCol2;
    float3 uCol3;
    float3 uCol4;
    float uSky;
    float uDayCos;
    float uGain;
    float uExposure;
    float uWhiteSqrRcp;
};

struct XmbMoodSourceUniforms {
    float2 uFoot;
    float uThreshold;
};

// The whole screen, vUv from the bottom left as GL has it.
vertex XmbMoodQuadVarying xmbMood_quadVertex(uint vid [[vertex_id]]) {
    float2 aPos = float2((vid & 1) != 0 ? 1.0 : -1.0, (vid & 2) != 0 ? 1.0 : -1.0);
    XmbMoodQuadVarying out;
    out.vUv = aPos * 0.5 + 0.5;
    out.position = float4(aPos, 0.0, 1.0);
    return out;
}

// A picture drawn into a Metal target has its first row at the top, where GL's has it at the bottom.
static float2 xmbMood_target(float2 uv) {
    return float2(uv.x, 1.0 - uv.y);
}

static float3 xmbMood_sky(float2 uv, float dayCos) {
    float d1 = dot(uv - 1.5, uv - 1.5);
    float2 q = uv - float2(0.4, 0.1);
    float e2 = q.x * q.x / 6.0 + q.y * q.y;
    float r = 0.4 * exp(0.4 * (uv.y - 1.0)) + 0.6 * exp(-16.0 * (dayCos + 1.0) * e2) + 0.375 * exp(-d1);
    float g = 0.3 * exp(0.2 * (uv.y - 1.0)) + 0.3 * exp(-d1) + exp(-12.0 * e2);
    float b = 0.45 * exp(-e2) + 0.6 * exp(-d1) + exp(0.1 * (uv.y - 1.0));
    float s = clamp(2.5 * (uv.y - 0.2 * uv.x - 0.4), -10.0, 10.0);
    return float3(r, g, b) * (0.4 + 0.4 * tanh(s * 0.5));
}

// lib/moyou/back_colours1 (its sky, brighter toward the top, a glow low on the left that swells with _DayTime), times
// BACKGROUND colours 1 to 2 from the bottom up; the lines in colours 3 to 4 (LinesController); then glutils ToneMap.
fragment float4 xmbMood_sceneFragment(XmbMoodQuadVarying in [[stage_in]],
                                      texture2d<float> uLines [[texture(0)]],
                                      constant XmbMoodSceneUniforms &u [[buffer(0)]]) {
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    float3 back = u.uSky > 0.5 ? xmbMood_sky(in.vUv, u.uDayCos) : float3(1.0);
    float3 c = back * mix(u.uCol1, u.uCol2, in.vUv.y);
    c += min(uLines.sample(nearest, xmbMood_target(in.vUv)).r, 0.53) * u.uGain * mix(u.uCol3, u.uCol4, in.vUv.x);
    float3 y = c * u.uExposure;
    return float4(y * (1.0 + y * u.uWhiteSqrRcp) / (1.0 + y), 1.0);
}

// glutils GlareSourceMRT: what is past the threshold, here averaged over the 128x64 texel.
fragment float4 xmbMood_sourceFragment(XmbMoodQuadVarying in [[stage_in]],
                                       texture2d<float> uScene [[texture(0)]],
                                       constant XmbMoodSourceUniforms &u [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 s = float3(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            float2 o = (float2(float(i), float(j)) - 1.5) * 0.25 * u.uFoot;
            s += max(uScene.sample(linear, xmbMood_target(in.vUv + o)).rgb - u.uThreshold, 0.0);
        }
    }
    return float4(s / 16.0, 0.0);
}

// glutils GlareSource and AccGlare: a copy, at a weight.
fragment float4 xmbMood_copyFragment(XmbMoodQuadVarying in [[stage_in]],
                                     texture2d<float> uTex [[texture(0)]],
                                     constant float &uWeight [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    return float4(uTex.sample(linear, xmbMood_target(in.vUv)).rgb * uWeight, 0.0);
}

// glutils Gaussian: the middle and seven texels to each side, a weight for each colour.
fragment float4 xmbMood_blurFragment(XmbMoodQuadVarying in [[stage_in]],
                                     texture2d<float> uTex [[texture(0)]],
                                     constant float2 &uStep [[buffer(0)]],
                                     constant float3 *uW [[buffer(1)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float2 uv = xmbMood_target(in.vUv);
    float3 s = uTex.sample(linear, uv).rgb * uW[0];
    for (int k = 1; k < 8; k++) {
        float2 o = uStep * float(k);
        s += (uTex.sample(linear, uv + o).rgb + uTex.sample(linear, uv - o).rgb) * uW[k];
    }
    return float4(s, 0.0);
}

// glutils ToneApplyDisplay: the glare added to the scene.
fragment float4 xmbMood_outFragment(XmbMoodQuadVarying in [[stage_in]],
                                    texture2d<float> uScene [[texture(0)]],
                                    texture2d<float> uGlare [[texture(1)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float2 uv = xmbMood_target(in.vUv);
    return float4(uScene.sample(linear, uv).rgb + uGlare.sample(linear, uv).rgb, 1.0);
}
