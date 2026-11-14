// SPDX-License-Identifier: GPL-2.0-only
// The XMB's sparkles as the console's particles_quads and particles_second shaders draw them, translated from the GLSL in Android's XmbParticles.kt.

#include <metal_stdlib>
using namespace metal;

struct XmbSparkleInstance {
    float4 place;
    float4 attitude;
};

struct XmbSparkleUniforms {
    float2 scale;
    float level;
    float tint;
    float glare;
};

struct XmbSparkleVaryings {
    float4 position [[position]];
    float2 uv;
    float2 blur;
    float alpha;
    float3 normal;
    float3 world;
};

// The constants an RSX capture of the console's XMB showed the shaders drawn with.
static constant float3 xmbSparkleCamera = float3(0.0, 0.0, 2.0);
static constant float3 xmbSparkleLifeMin = float3(-10.0, -10.0, -12.0);
static constant float3 xmbSparkleLifeMax = float3(10.0, 10.0, 7.0);
static constant float4 xmbSparkleFocus = float4(6.12435, 7.44365, 12.2008, 16.228149);
static constant float3 xmbSparkleFocusCurves = float3(2.47206, 1.83324, 0.925025);
static constant float3 xmbSparkleSize = float3(0.046477, 0.077203, 0.874062);
static constant float3 xmbSparkleNear = float3(13.19, 3.79, 0.722);
static constant float2 xmbSparkleDark = float2(6.24028, 13.9);
static constant float2 xmbSparkleTransparency = float2(1.33319, 1.0);
static constant float xmbSparkleGlareSize = 5.44386;
static constant float3 xmbSparkleLight = float3(4.16, 2.63, -7.6);
static constant float xmbSparkleLambert = 8.0;
static constant float xmbSparkleSpecular = 74.74;
static constant float xmbSparkleSpecularPower = 35.2904;
static constant float xmbSparkleExposure = 0.0390458;
static constant float xmbSparkleNearFuzziness = 3.79;
static constant float2 xmbSparkleGlare = float2(0.201367, 4.44);

static float3x3 xmbSparkleTurn(float4 q) {
    return float3x3(
        float3(1.0 - 2.0 * (q.y * q.y + q.z * q.z), 2.0 * (q.x * q.y + q.w * q.z), 2.0 * (q.x * q.z - q.w * q.y)),
        float3(2.0 * (q.x * q.y - q.w * q.z), 1.0 - 2.0 * (q.x * q.x + q.z * q.z), 2.0 * (q.y * q.z + q.w * q.x)),
        float3(2.0 * (q.x * q.z + q.w * q.y), 2.0 * (q.y * q.z - q.w * q.x), 1.0 - 2.0 * (q.x * q.x + q.y * q.y)));
}

vertex XmbSparkleVaryings xmbSparkle_vertex(uint vertexID [[vertex_id]], uint instanceID [[instance_id]],
                                            const device XmbSparkleInstance *instances [[buffer(0)]],
                                            constant XmbSparkleUniforms &uniforms [[buffer(1)]]) {
    const float4 focus = xmbSparkleFocus;
    const float4 upright = float4(0.0, 0.0, 0.0, 1.0);
    XmbSparkleInstance instance = instances[instanceID];
    float2 corner = float2(vertexID & 1, vertexID >> 1);
    bool glare = uniforms.glare > 0.5;

    float3 pos = instance.place.xyz;
    // Fading out within 0.2 of the edge of its box.
    float3 edge = min(pos - xmbSparkleLifeMin, xmbSparkleLifeMax - pos);
    float f = clamp(min(edge.x, min(edge.y, edge.z)) * 5.0, 0.0, 1.0);
    float boxFade = f * f * (3.0 - 2.0 * f);
    float4 q = normalize(instance.attitude);
    float3 toCamera = xmbSparkleCamera - pos;
    float dist = length(toCamera);
    float3 V = toCamera / dist;
    // Depth of field: nearer than 7.44 grows towards the near size, beyond 12.2 towards the far one.
    float nearT = (clamp(dist, focus.x, focus.y) - focus.y) / (focus.x - focus.y);
    float farT = (clamp(dist, focus.z, focus.w) - focus.z) / (focus.w - focus.z);
    float nearP = pow(nearT, xmbSparkleFocusCurves.x);
    float farP = pow(farT, xmbSparkleFocusCurves.y);
    float size = xmbSparkleSize.x + nearP * (xmbSparkleSize.y - xmbSparkleSize.x);
    size += farP * (xmbSparkleSize.z - size);
    float rampA = (clamp(dist, focus.z - 0.2, focus.z) - (focus.z - 0.2)) * 5.0;
    float rampB = (clamp(dist, focus.z - 0.6, focus.z - 0.4) - (focus.z - 0.6)) * 5.0;
    float rampC = (clamp(dist, focus.z - 0.4, focus.z - 0.2) - (focus.z - 0.4)) * 5.0;
    float midFade = 1.0 + rampA - rampB;
    float angle = 2.0 * atan(size / dist) / xmbSparkleFocusCurves.z;
    float farDark = clamp(farT * angle * xmbSparkleDark.y, 0.0, 1.0);
    float nearDark = clamp(nearT * angle * xmbSparkleDark.x, 0.0, 1.0);
    // Near and large flakes turn to face the camera; the glare always does, 5.44 times the size.
    float face = clamp(angle * xmbSparkleNear.x + nearP * xmbSparkleNear.z + rampC, 0.0, 1.0);
    float4 facing = glare ? upright : normalize(mix(q, upright, face));
    // Half the particle size across, which is what the console's flakes and glare measure in its frame dumps.
    float s = 0.5 * (glare ? size * xmbSparkleGlareSize : size);
    float3x3 R = xmbSparkleTurn(facing);
    float2 c = corner - 0.5;
    float3 world = pos + (R[0] * c.x + R[1] * c.y) * s;
    float w = 2.0 - world.z;

    XmbSparkleVaryings out;
    // The console's depth runs -w to w; Metal clips 0 to w, so it is brought into that.
    out.position = float4(uniforms.scale.x * world.x, uniforms.scale.y * world.y, (1.0002 * w - 0.2 + w) * 0.5, w);
    // Seen edge on, a flake all but vanishes.
    float edgeOn = 1.0 - pow(max(1.0 - abs(dot(V, R[2])), 1e-6), xmbSparkleTransparency.x);
    out.alpha = edgeOn * xmbSparkleTransparency.y * instance.place.w * midFade * (1.0 - farDark) * (1.0 - nearDark) * boxFade;
    out.uv = corner;
    out.blur = float2(nearP, farP);
    // Lit by its own turning, however much it faces the camera.
    out.normal = xmbSparkleTurn(q)[2];
    out.world = world;
    return out;
}

fragment float4 xmbSparkle_fragment(XmbSparkleVaryings in [[stage_in]], constant XmbSparkleUniforms &uniforms [[buffer(1)]],
                                    texture2d<float> swirl [[texture(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float3 N = normalize(in.normal);
    // The swirl looked up by the flake's normal as the camera sees it.
    float3 colour = mix(float3(1.0), swirl.sample(linear, N.xy * 0.5 + 0.5).rgb, uniforms.tint);
    float3 V = normalize(xmbSparkleCamera - in.world);
    float3 L = normalize(xmbSparkleLight - in.world);
    float3 H = normalize(V + L);
    float highlight = pow(abs(dot(N, H)), xmbSparkleSpecularPower);
    float r = length(in.uv - 0.5);
    float3 lit;
    if (uniforms.glare < 0.5) {
        // A disc, its edge softer the more out of focus it is.
        float blur = clamp(in.blur.x + in.blur.y, 0.0, 1.0);
        float e = blur * 0.6345 + 0.05;
        float t = clamp((r - (0.5 - e)) / (2.0 * e), 0.0, 1.0);
        float disc = 1.0 - t * t * (3.0 - 2.0 * t);
        float fuzz = in.blur.x * (xmbSparkleNearFuzziness - 0.85) + 0.85;
        float profile = disc + blur * (1.0 - disc - exp(-disc * fuzz));
        // Round to the end: a very blurred flake's soft edge would otherwise show the square's corners.
        profile *= 1.0 - smoothstep(0.45, 0.5, r);
        float3 light = abs(dot(N, L)) * xmbSparkleLambert + highlight * xmbSparkleSpecular * colour;
        lit = (1.0 - exp(-light * xmbSparkleExposure)) * profile * in.alpha;
    } else {
        // The flash's glare: only the highlight, in a glow from the middle.
        float glow = exp(-pow(clamp(r, 0.0, 1.0), 0.99) * xmbSparkleGlare.y) * (1.0 - smoothstep(0.35, 0.5, r));
        lit = (1.0 - exp(-highlight * xmbSparkleSpecular * colour * xmbSparkleExposure)) * in.alpha * glow * xmbSparkleGlare.x;
    }
    // Added on, as the console blends them, so the layer's own alpha stays 0.
    return float4(lit * uniforms.level, 0.0);
}
