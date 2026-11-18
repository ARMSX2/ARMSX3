// SPDX-License-Identifier: GPL-2.0-only
// XMB mode's background gradient and the console's wave, translated from the GLSL in Android's XmbGlView.kt and XmbFirmwareWave.kt.

#include <metal_stdlib>
using namespace metal;

struct XmbWaveGradientUniforms {
    float3 uColorStart;
    float3 uColorEnd;
    float2 uDir;
    float uTMin;
};

struct XmbWaveLineUniforms {
    float uFresnel;
    float uBrightness;
    float uMipBias;
};

struct XmbWaveCompositeUniforms {
    float3 uCol3;
    float3 uCol4;
    float uGain;
};

struct XmbWaveQuadVarying {
    float4 position [[position]];
    float2 vUv;
    float2 vUvYDown;
};

struct XmbWaveLineVarying {
    float4 position [[position]];
    float2 vTc0;
    float4 vTc1;
};

// The whole screen as a four-vertex strip.
vertex XmbWaveQuadVarying xmbWave_quadVertex(uint vid [[vertex_id]]) {
    float2 aPos = float2((vid & 1) != 0 ? 1.0 : -1.0, (vid & 2) != 0 ? 1.0 : -1.0);
    XmbWaveQuadVarying out;
    out.vUv = aPos * 0.5 + 0.5;
    out.vUvYDown = float2(aPos.x * 0.5 + 0.5, 1.0 - (aPos.y * 0.5 + 0.5));
    out.position = float4(aPos, 0.0, 1.0);
    return out;
}

// The month's gradient. Android's uTSpan is 1 and its uSmooth 0 for this one, so neither is here.
fragment float4 xmbWave_gradientFragment(XmbWaveQuadVarying in [[stage_in]],
                                         constant XmbWaveGradientUniforms &u [[buffer(0)]]) {
    float t = dot(in.vUvYDown, u.uDir);
    float g = clamp(t - u.uTMin, 0.0, 1.0);
    return float4(mix(u.uColorStart, u.uColorEnd, g), 1.0);
}

static void xmbWave_basis(float t, thread float4 &b, thread float4 &d) {
    float t2 = t * t;
    float t3 = t2 * t;
    float m = 1.0 - t;
    b = float4(m * m * m, 3.0 * t3 - 6.0 * t2 + 4.0, -3.0 * t3 + 3.0 * t2 + 3.0 * t + 1.0, t3) / 6.0;
    d = float4(-3.0 * m * m, 9.0 * t2 - 12.0 * t, -9.0 * t2 + 6.0 * t + 3.0, 3.0 * t2) / 6.0;
}

// The 19x19 control points as the console's 128x128 grid of uniform cubic B-spline patches, 8 samples a patch at
// t = 16j/127, normals from the two tangents, lit as its lines shader lights them. The vertex index is row * 128 + column.
vertex XmbWaveLineVarying xmbWave_lineVertex(uint vid [[vertex_id]],
                                             constant float4 *uCtrl [[buffer(0)]],
                                             constant XmbWaveLineUniforms &u [[buffer(1)]]) {
    int r = int(vid / 128);
    int c = int(vid % 128);
    float2 aRC = float2(float(r), float(c));
    int pr = r / 8;
    int pc = c / 8;
    float4 br, dr, bc, dc;
    xmbWave_basis(float(r - pr * 8) * 16.0 / 127.0, br, dr);
    xmbWave_basis(float(c - pc * 8) * 16.0 / 127.0, bc, dc);
    float4 P = float4(0.0);
    float4 Pr = float4(0.0);
    float4 Pc = float4(0.0);
    for (int a = 0; a < 4; a++) {
        float4 rowP = float4(0.0);
        float4 rowD = float4(0.0);
        for (int b = 0; b < 4; b++) {
            float4 C = uCtrl[(pr + a) * 19 + pc + b];
            rowP += bc[b] * C;
            rowD += dc[b] * C;
        }
        P += br[a] * rowP;
        Pr += dr[a] * rowP;
        Pc += br[a] * rowD;
    }
    float3 N = cross(Pr.xyz, Pc.xyz);
    float lp = max(length(P.xyz), 1e-6);
    float3 v = P.xyz / lp;
    float w = abs(dot(v, N)) / lp * u.uMipBias;
    float vn = abs(dot(v, N / max(length(N), 1e-20)));
    float fr = min(1.0, 10.0 * min(aRC.x, 127.0 - aRC.x) / 127.0);
    float fc = min(1.0, 10.0 * min(aRC.y, 127.0 - aRC.y) / 127.0);
    XmbWaveLineVarying out;
    out.vTc0 = float2(aRC.x / 127.0, w);
    out.vTc1 = float4(u.uFresnel * w, u.uBrightness * w, vn * w, fr * fc);
    // The model's depth runs -w...w as OpenGL clips it; Metal clips 0...w.
    out.position = float4(P.xy, (P.z + P.w) * 0.5, P.w);
    return out;
}

// The band's own light (its stripe texture's rows of 127, 5, 0, 0 by how small the band is on screen) and the
// strands, where the band turns edge on.
fragment float4 xmbWave_lineFragment(XmbWaveLineVarying in [[stage_in]], texture2d<float> uStrands [[texture(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float y = clamp(in.vTc0.y * 4.0 - 0.5, 0.0, 3.0);
    float s = (y < 1.0 ? mix(127.0, 5.0, y) : (y < 2.0 ? mix(5.0, 0.0, y - 1.0) : 0.0)) / 255.0;
    float strand = uStrands.sample(linear, float2(in.vTc1.z, 0.5)).r;
    float light = (in.vTc1.y * s + strand * in.vTc1.x) * in.vTc1.w;
    return float4(clamp(light, 0.0, 1.0), 0.0, 0.0, 0.0);
}

// The light, which the console's two 8-bit channels hold up to about 0.53, in the line colour, from colour 3 at
// the left to colour 4 at the right.
fragment float4 xmbWave_compositeFragment(XmbWaveQuadVarying in [[stage_in]],
                                          texture2d<float> uLines [[texture(0)]],
                                          constant XmbWaveCompositeUniforms &u [[buffer(0)]]) {
    constexpr sampler nearest(filter::nearest, address::clamp_to_edge);
    // A Metal texture's first row is the top of the picture drawn into it.
    float light = min(uLines.sample(nearest, in.vUvYDown).r, 0.53);
    return float4(mix(u.uCol3, u.uCol4, in.vUv.x) * light * u.uGain, 0.0);
}
