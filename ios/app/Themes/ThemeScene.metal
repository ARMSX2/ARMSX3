// SPDX-License-Identifier: GPL-2.0-only
// Live theme shaders, prefixed themeScene_ because the lifted XMB library probes the default library for its own names.

#include <metal_stdlib>
using namespace metal;

// Three float4 rows a joint; ThemeSceneRenderer.swift mirrors these layouts, so change both together.
constant int themeScene_maxBones = 72;
constant int themeScene_maxLights = 4;

// One 40-byte vertex: position, uv, normal as floats; four joint indexes and four normalised weights as bytes.
struct ThemeSceneVertexIn {
    float3 position [[attribute(0)]];
    float2 uv [[attribute(1)]];
    float3 normal [[attribute(2)]];
    uchar4 joints [[attribute(3)]];
    float4 weights [[attribute(4)]];
};

// Per draw, the same bytes to the vertex and the fragment function.
struct ThemeSceneDraw {
    float4x4 mvp;
    float4x4 model;
    float4 uv;        // scale in xy, offset in zw
    float4 color;     // the actor's RGBA multiplier, not premultiplied
    float skinned;    // 1: bend by the palette
    float opaque;     // 1: no blending, alpha written as 1
    float lit;        // 1: shade by the lights
    float alphaCut;   // above 0: pixels less solid than this are left out
    float straight;   // 1: the texture's data is straight alpha (BC) and is premultiplied here
    int block;        // 1, 3 or 5: the texture is that DXT's blocks in `blocks`, decoded here; 0: sample `tex`
    int2 size;        // the block texture's size in pixels (not blocks)
};

struct ThemeSceneLights {
    float4 ambient;                            // rgb
    float4 position[themeScene_maxLights];     // w 1: a point light's position; w 0: toward a directional light
    float4 color[themeScene_maxLights];        // rgb
    int count;
};

struct ThemeSceneVarying {
    float4 position [[position]];
    float2 uv;
    float3 world;
    float3 normal;
};

static float3 themeScene_bone(constant float4 *bones, int j, float4 p) {
    return float3(dot(bones[3 * j], p), dot(bones[3 * j + 1], p), dot(bones[3 * j + 2], p));
}

vertex ThemeSceneVarying themeScene_vertex(ThemeSceneVertexIn in [[stage_in]],
                                           constant ThemeSceneDraw &draw [[buffer(1)]],
                                           constant float4 *bones [[buffer(2)]]) {
    float3 p = in.position;
    float3 n = in.normal;
    if (draw.skinned > 0.5) {
        float4 w = in.weights;
        float total = w.x + w.y + w.z + w.w;
        // Byte weights rarely sum to exactly 1; a short sum would shrink the vertex toward the origin.
        if (total > 0.0 && total < 1.0) w /= total;
        p = float3(0.0);
        n = float3(0.0);
        for (int k = 0; k < 4; k++) {
            if (w[k] > 0.0) {
                int j = min(int(in.joints[k]), themeScene_maxBones - 1);
                p += w[k] * themeScene_bone(bones, j, float4(in.position, 1.0));
                n += w[k] * themeScene_bone(bones, j, float4(in.normal, 0.0));
            }
        }
    }
    ThemeSceneVarying out;
    out.position = draw.mvp * float4(p, 1.0);
    out.uv = in.uv * draw.uv.xy + draw.uv.zw;
    out.world = (draw.model * float4(p, 1.0)).xyz;
    out.normal = float3x3(draw.model[0].xyz, draw.model[1].xyz, draw.model[2].xyz) * n;
    return out;
}

static int themeScene_expand5(uint v) { return int((v << 3) | (v >> 2)); }
static int themeScene_expand6(uint v) { return int((v << 2) | (v >> 4)); }

// The integer maths matches P3tAnimation's, so the GPU and the CPU decode a pixel to the same bytes.
static float4 themeScene_decodeTexel(texture2d<uint> blocks, int kind, int2 size, int2 p) {
    p = ((p % size) + size) % size;
    uint4 w = blocks.read(uint2(p >> 2));
    int k = ((p.y & 3) << 2) | (p.x & 3);
    uint colors = kind == 1 ? w.x : w.z;
    uint indexes = kind == 1 ? w.y : w.w;
    uint c0 = colors & 0xFFFFu, c1 = colors >> 16;
    int3 e0 = int3(themeScene_expand5(c0 >> 11), themeScene_expand6((c0 >> 5) & 63u), themeScene_expand5(c0 & 31u));
    int3 e1 = int3(themeScene_expand5(c1 >> 11), themeScene_expand6((c1 >> 5) & 63u), themeScene_expand5(c1 & 31u));
    uint i = (indexes >> uint(2 * k)) & 3u;
    int3 rgb;
    int a = 255;
    if (i == 0u) rgb = e0;
    else if (i == 1u) rgb = e1;
    else if (kind != 1 || c0 > c1) rgb = i == 2u ? (2 * e0 + e1) / 3 : (e0 + 2 * e1) / 3;
    else if (i == 2u) rgb = (e0 + e1) / 2;
    else { rgb = int3(0); a = 0; } // DXT1's three-colour mode: the fourth is transparent black
    if (kind == 3) {
        a = int(((k < 8 ? w.x : w.y) >> uint(4 * (k & 7))) & 15u) * 17;
    } else if (kind == 5) {
        int a0 = int(w.x & 255u), a1 = int((w.x >> 8) & 255u);
        // Three bits a pixel, from bit 16 of the first word on, running into the second.
        uint at = uint(16 + 3 * k);
        uint bits = at >= 32u ? w.y >> (at - 32u) : (w.x >> at) | (w.y << (32u - at));
        int j = int(bits & 7u);
        if (j == 0) a = a0;
        else if (j == 1) a = a1;
        else if (a0 > a1) a = ((8 - j) * a0 + (j - 1) * a1) / 7;
        else if (j == 6) a = 0;
        else if (j == 7) a = 255;
        else a = ((6 - j) * a0 + (j - 1) * a1) / 5;
    }
    float alpha = float(a) / 255.0;
    return float4(float3(rgb) / 255.0 * alpha, alpha);
}

// Integer textures are fetched, never sampled, so the four pixels around uv are filtered here.
static float4 themeScene_blockSample(texture2d<uint> blocks, int kind, int2 size, float2 uv) {
    float2 st = uv * float2(size) - 0.5;
    float2 f = fract(st);
    int2 p = int2(floor(st));
    float4 a = themeScene_decodeTexel(blocks, kind, size, p);
    float4 b = themeScene_decodeTexel(blocks, kind, size, p + int2(1, 0));
    float4 c = themeScene_decodeTexel(blocks, kind, size, p + int2(0, 1));
    float4 d = themeScene_decodeTexel(blocks, kind, size, p + int2(1, 1));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

fragment float4 themeScene_fragment(ThemeSceneVarying in [[stage_in]],
                                    constant ThemeSceneDraw &draw [[buffer(1)]],
                                    constant ThemeSceneLights &lights [[buffer(2)]],
                                    texture2d<float> tex [[texture(0)]],
                                    texture2d<uint> blocks [[texture(1)]]) {
    constexpr sampler repeatTrilinear(address::repeat, filter::linear, mip_filter::linear);
    float4 t;
    if (draw.block != 0) {
        t = themeScene_blockSample(blocks, draw.block, max(draw.size, int2(1)), in.uv);
    } else {
        t = tex.sample(repeatTrilinear, in.uv);
        if (draw.straight > 0.5) t.rgb *= t.a;
    }
    if (t.a * draw.color.a < draw.alphaCut) discard_fragment();
    float3 rgb = t.rgb;
    if (draw.lit > 0.5) {
        float3 n = normalize(in.normal);
        float3 shade = lights.ambient.rgb;
        for (int i = 0; i < themeScene_maxLights; i++) {
            if (i >= lights.count) break;
            float4 l4 = lights.position[i];
            float3 l = l4.w > 0.5 ? normalize(l4.xyz - in.world) : normalize(l4.xyz);
            shade += lights.color[i].rgb * max(dot(n, l), 0.0);
        }
        rgb *= min(shade, float3(1.0));
    }
    return draw.opaque > 0.5 ? float4(rgb * draw.color.rgb, 1.0)
                             : float4(rgb * draw.color.rgb * draw.color.a, t.a * draw.color.a);
}
