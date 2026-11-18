// SPDX-License-Identifier: GPL-2.0-only
// Gaia's Earth, air, sun, exposure and glare, translated from the GLSL in Android's XmbGaia.kt (the firmware's CubeEarthLayer, Sun, SunSpike and glutils).

#include <metal_stdlib>
using namespace metal;

struct XmbGaiaQuadVarying {
    float4 position [[position]];
    float2 vUv;
};

struct XmbGaiaSpaceUniforms {
    float4x4 uInvViewProj;
    float3 uEye;
    float3 uSun;
    float4 uCloud;
    float2 uShadow;
    float4 uSea;
    float3 uInvWave4;
    float3 uSunColour;
    float uSunIntensity;
    float2 uAttn;
    float3 uStars;
    float uFade;
};

// What the sun's shaders need to find, a pixel at a time, whether the Earth is in the way.
struct XmbGaiaSight {
    float4x4 uInvViewProj;
    float3 uEye;
    float2 uViewport;
};

struct XmbGaiaSunUniforms {
    XmbGaiaSight sight;
    float uBright;
    float3 uBeta;
};

struct XmbGaiaSpikeUniforms {
    XmbGaiaSight sight;
    float2 uFalloff;
    float4 uColour;
};

struct XmbGaiaSpikeShape {
    float4 uSpike;
    float2 uSize;
};

struct XmbGaiaSourceUniforms {
    float2 uFoot;
    float uThreshold;
    float uExposure;
    float uWhiteSqrRcp;
    float uAuto;
};

struct XmbGaiaLumUniforms {
    float2 uFoot;
    float uWhite;
};

struct XmbGaiaExposureUniforms {
    float uTop;
    float uKey;
    float2 uRange;
    float uSpeed;
};

struct XmbGaiaOutUniforms {
    float uExposure;
    float uWhiteSqrRcp;
    float uAuto;
    float uGamma;
    float uDither;
    float uSeed;
};

constant float XMB_GAIA_R_IN = 1.0;
constant float XMB_GAIA_R_OUT = 1.025;

// A picture drawn into a Metal target has its first row at the top, where GL's has it at the bottom.
static float2 xmbGaia_target(float2 uv) {
    return float2(uv.x, 1.0 - uv.y);
}

vertex XmbGaiaQuadVarying xmbGaia_quadVertex(uint vid [[vertex_id]]) {
    float2 aPos = float2((vid & 1) != 0 ? 1.0 : -1.0, (vid & 2) != 0 ? 1.0 : -1.0);
    XmbGaiaQuadVarying out;
    out.vUv = aPos * 0.5 + 0.5;
    out.position = float4(aPos, 0.0, 1.0);
    return out;
}

// The air: its edge, and its optical depth made once (XmbGaiaFirmware.opticalDepth) from height r toward the air's
// edge at angle acos(mu) from the vertical, in the air's thickness, over the directions above the horizon (finer
// toward it). The cosine of the horizon's angle from the vertical at height r:
static float xmbGaia_horizon(float r) {
    return -sqrt(max(0.0, 1.0 - 1.0 / (r * r)));
}

static float xmbGaia_od(texture2d<float> uOd, float r, float mu) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float mh = xmbGaia_horizon(r);
    float x = sqrt(clamp((mu - mh) / (1.0 - mh), 0.0, 1.0));
    float y = clamp((r - XMB_GAIA_R_IN) / (XMB_GAIA_R_OUT - XMB_GAIA_R_IN), 0.0, 1.0);
    return uOd.sample(linear, float2(x, y)).r;
}

static float2 xmbGaia_sphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float h = b * b - c;
    if (h < 0.0) return float2(1e9, -1e9);
    h = sqrt(h);
    return float2(-b - h, -b + h);
}

// Whether the sun is above the horizon at height r, the Earth's shadow edge softened a little.
static float xmbGaia_sunlit(float r, float mus) {
    float mh = xmbGaia_horizon(r);
    return smoothstep(mh - 0.02, mh + 0.02, mus);
}

// Space, a pixel at a time: the line of sight from the eye, the stars behind, the Earth it meets (CubeEarthLayer: the
// sea to its blue, clouds lifted by their thickness and their shadows, the glint), and the air along it, scattering
// the sunlight in the console's colours.
fragment float4 xmbGaia_spaceFragment(XmbGaiaQuadVarying in [[stage_in]],
                                      constant XmbGaiaSpaceUniforms &u [[buffer(0)]],
                                      texturecube<float> uGround [[texture(0)]],
                                      texturecube<float> uClouds [[texture(1)]],
                                      texturecube<float> uSpec [[texture(2)]],
                                      texturecube<float> uStarTex [[texture(3)]],
                                      texture2d<float> uOd [[texture(4)]]) {
    constexpr sampler cube(filter::linear, mip_filter::linear, address::clamp_to_edge);
    const float SCALE_H = 0.25;
    const float KR = 0.0025;
    const float KM = 0.0010;
    const float G_MIE = -0.95;
    const float PI = 3.14159265;

    float4 ndc = float4(in.vUv * 2.0 - 1.0, 1.0, 1.0);
    float4 far = u.uInvViewProj * ndc;
    float3 dir = normalize(far.xyz / far.w - u.uEye);

    // The stars: TiledStars' curve, 2^(8.64 c POWSCALE) times the factor, toward white by WHITENESS.
    float3 s = uStarTex.sample(cube, dir).rgb;
    s = mix(s, float3((s.r + s.g + s.b) / 3.0), u.uStars.z);
    float3 col = (exp2(8.64386 * s * u.uStars.y) - 1.0) * u.uStars.x * 0.05;

    float2 atm = xmbGaia_sphere(u.uEye, dir, XMB_GAIA_R_OUT);
    float2 ground = xmbGaia_sphere(u.uEye, dir, XMB_GAIA_R_IN);
    float3 sunLight = u.uSunColour * u.uSunIntensity;

    if (atm.y > 0.0) {
        float t0 = max(atm.x, 0.0);
        // In front of the eye; a line past the Earth has no ground (sphere() gives it 1e9, -1e9).
        bool hit = ground.x > 0.0 && ground.y > 0.0;
        float t1 = hit ? ground.x : atm.y;
        // Light lost along the way: Rayleigh by 1/wavelength^4 in the console's colours, and Mie.
        float3 beta = u.uInvWave4 * KR * 4.0 * PI + KM * 4.0 * PI;
        float thick = XMB_GAIA_R_OUT - XMB_GAIA_R_IN;

        // Along the line, the sunlight scattered toward the eye, each sample's share dimmed by the air between it and
        // the sun and between it and the eye.
        const int N = 12;
        float seg = (t1 - t0) / float(N);
        float3 sumR = float3(0.0);
        float3 sumM = float3(0.0);
        for (int i = 0; i < N; i++) {
            float3 sp = u.uEye + dir * (t0 + seg * (float(i) + 0.5));
            float h = length(sp);
            float3 up = sp / h;
            float dens = exp(-(h - XMB_GAIA_R_IN) / (SCALE_H * thick));
            float mus = dot(u.uSun, up);
            float tau = xmbGaia_od(uOd, h, mus) + xmbGaia_od(uOd, h, dot(-dir, up));
            float3 tr = exp(-tau * beta) * xmbGaia_sunlit(h, mus);
            float3 add = tr * dens * (seg / thick);
            sumR += add;
            sumM += add;
        }
        float cosA = dot(u.uSun, -dir);
        float rayleigh = 0.75 * (1.0 + cosA * cosA);
        float g2 = G_MIE * G_MIE;
        float mie = 1.5 * ((1.0 - g2) / (2.0 + g2)) * (1.0 + cosA * cosA) / pow(1.0 + g2 - 2.0 * G_MIE * cosA, 1.5);
        // At the preset's ATTN ATM., as the console's air over the Earth and against space.
        float3 inscatter = (sumR * u.uInvWave4 * KR * rayleigh + sumM * KM * mie) * sunLight * u.uAttn.y;
        // What lies behind, seen through all of the air along the line.
        float3 through = hit ? exp(-xmbGaia_od(uOd, XMB_GAIA_R_IN, dot(-dir, normalize(u.uEye + dir * ground.x))) * beta)
                             : exp(-(xmbGaia_od(uOd, length(u.uEye + dir * t0), dot(dir, normalize(u.uEye + dir * t0)))) * beta);

        if (hit) {
            float3 n = normalize(u.uEye + dir * ground.x);
            float3 e = -dir;
            // The ground: the sea taken to the firmware's blue, clouds lifted by their thickness (PARALLAX) over it,
            // their shadow cast away from the sun (SHADOW BIAS, STREN), and the sun's glint off the sea (SEA ALBEDO,
            // SEA POW) and the land (LAND ALBEDO, POW). The land and sea at the preset's ATTN EARTH; the clouds and
            // the glint are not.
            float3 land = uGround.sample(cube, n).rgb;
            float sea = uSpec.sample(cube, n).r;
            land = mix(land, float3(0.06, 0.15, 0.225), sea) * u.uAttn.x;
            float3 tangentEye = e - n * dot(e, n);
            float thickC = uClouds.sample(cube, n).r;
            float cloud = uClouds.sample(cube, normalize(n + tangentEye * (thickC * u.uCloud.x + u.uCloud.y) * 0.02)).r;
            float3 tangentSun = u.uSun - n * dot(u.uSun, n);
            float shadowCloud = uClouds.sample(cube, normalize(n + tangentSun * u.uShadow.x * 0.01)).r;
            float shadow = 1.0 - clamp(shadowCloud - cloud * 0.8, 0.0, 1.0) * u.uShadow.y * 0.6;
            float3 surface = mix(land, float3(0.92), cloud);
            // The firmware's glint: the eye's slant across the ground, N (N.E) - E, against the sun (strongest toward
            // the edge of the Earth, none straight down), at SEA or LAND POW and ALBEDO, beside the ground's own light
            // (its ATTN EARTH, 0.038).
            float facing = dot(n, e);
            float slant = clamp(dot(n * facing - e, u.uSun), 0.0, 1.0);
            float albedo = mix(u.uSea.z, u.uSea.x, sea) * (1.0 - cloud);
            float glint = albedo * pow(slant, mix(u.uSea.w, u.uSea.y, sea)) / 0.038;
            float3 glintColour = float3(0.8 + 0.0708 * (facing + 0.5), 0.788 * (facing + 0.5), 0.706 * (facing + 0.5));
            // Sunlight down through the air to the ground, and the blue of the sky lighting it too (none past the
            // terminator).
            float mus = dot(u.uSun, n);
            float3 sunGround = sunLight * exp(-xmbGaia_od(uOd, XMB_GAIA_R_IN, mus) * beta * 0.6) * 0.14;
            float3 skyLight = sunLight * float3(0.30, 0.45, 0.75) * 0.035 * smoothstep(-0.12, 0.35, mus);
            float lambert = max(mus, 0.0);
            float3 lit = (surface * (lambert * sunGround + skyLight) + glintColour * glint * sunGround * lambert) * shadow;
            col = lit * through + inscatter;
        } else {
            col = col * through + inscatter;
        }
    }
    // Kept finite; the glare and the exposure read it as the console's toned picture holds it.
    return float4(min(col, float3(24.0)) * u.uFade, 1.0);
}

// A pixel's line of sight, from GL's window position (from the bottom left), and how close it passes the Earth's
// centre: behind the Earth when that is inside it, through the air when inside the air (Sun.fpo and SunSpike.fpo's test).
static float3 xmbGaia_sight(constant XmbGaiaSight &s, float4 position) {
    float2 wpos = float2(position.x, s.uViewport.y - position.y);
    float4 far = s.uInvViewProj * float4(wpos / s.uViewport * 2.0 - 1.0, 1.0, 1.0);
    return normalize(far.xyz / far.w - s.uEye);
}

// The closest approach to the Earth's centre, or 1e9 when that is behind the eye.
static float xmbGaia_pass(constant XmbGaiaSight &s, float3 dir) {
    float b = dot(s.uEye, dir);
    return b < 0.0 ? length(s.uEye - dir * b) : 1e9;
}

// Sun.fpo: the firmware's sun picture where the sun is, at its BRIGHTNESS, hidden by the Earth and seen through the
// air past its edge (the light lost along the whole of that line).
vertex XmbGaiaQuadVarying xmbGaia_sunVertex(uint vid [[vertex_id]], constant float4 &uRect [[buffer(0)]]) {
    float2 aPos = float2((vid & 1) != 0 ? 1.0 : -1.0, (vid & 2) != 0 ? 1.0 : -1.0);
    XmbGaiaQuadVarying out;
    out.vUv = aPos * 0.5 + 0.5;
    out.position = float4(uRect.xy + aPos * uRect.zw, 0.0, 1.0);
    return out;
}

fragment float4 xmbGaia_sunFragment(XmbGaiaQuadVarying in [[stage_in]],
                                    constant XmbGaiaSunUniforms &u [[buffer(0)]],
                                    texture2d<float> uDisk [[texture(0)]],
                                    texture2d<float> uOd [[texture(1)]]) {
    constexpr sampler disk(filter::linear, mip_filter::linear, address::clamp_to_edge);
    float d = xmbGaia_pass(u.sight, xmbGaia_sight(u.sight, in.position));
    if (d < XMB_GAIA_R_IN) discard_fragment();
    // Both halves of the line through the air, from its closest point out to the air's edge.
    float3 through = d < XMB_GAIA_R_OUT ? exp(-2.0 * xmbGaia_od(uOd, d, 0.0) * u.uBeta) : float3(1.0);
    float3 c = uDisk.sample(disk, in.vUv).rgb;
    return float4(c * c * u.uBright * 6.0 * through, 0.0);
}

// SunSpike: a long thin streak from the sun, falling off by FALLOFF X along and Y across.
vertex XmbGaiaQuadVarying xmbGaia_spikeVertex(uint vid [[vertex_id]], constant XmbGaiaSpikeShape &u [[buffer(0)]]) {
    float2 aPos = float2((vid & 1) != 0 ? 1.0 : -1.0, (vid & 2) != 0 ? 1.0 : -1.0);
    XmbGaiaQuadVarying out;
    out.vUv = aPos * 0.5 + 0.5;
    float c = cos(u.uSpike.z);
    float s = sin(u.uSpike.z);
    float2 p = float2(aPos.x * u.uSize.x, aPos.y * u.uSize.y);
    float2 r = float2(p.x * c - p.y * s, p.x * s + p.y * c);
    out.position = float4(u.uSpike.xy + float2(r.x / u.uSpike.w, r.y), 0.0, 1.0);
    return out;
}

fragment float4 xmbGaia_spikeFragment(XmbGaiaQuadVarying in [[stage_in]], constant XmbGaiaSpikeUniforms &u [[buffer(0)]]) {
    if (xmbGaia_pass(u.sight, xmbGaia_sight(u.sight, in.position)) < 1.0) discard_fragment();
    float2 q = abs(in.vUv - 0.5);
    float v = exp(-(q.x * u.uFalloff.x * 0.5 + q.y * u.uFalloff.y * 2.0));
    v *= 1.0 - smoothstep(0.35, 0.5, q.x);
    return float4(u.uColour.rgb * v, 0.0);
}

// GlareSourceNoTone: past GLARE THRESH in the toned picture, as the console's 8-bit one holds it (no brighter than 1),
// averaged over the glare's texel.
fragment float4 xmbGaia_sourceFragment(XmbGaiaQuadVarying in [[stage_in]],
                                       constant XmbGaiaSourceUniforms &u [[buffer(0)]],
                                       texture2d<float> uScene [[texture(0)]],
                                       texture2d<float> uExpTex [[texture(1)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float exposure = u.uAuto > 0.5 ? uExpTex.sample(linear, float2(0.5)).r : u.uExposure;
    float3 s = float3(0.0);
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            float2 o = (float2(float(i), float(j)) - 1.5) * 0.25 * u.uFoot;
            float3 y = uScene.sample(linear, xmbGaia_target(in.vUv + o)).rgb * exposure;
            float3 t = y * (1.0 + y * u.uWhiteSqrRcp) / (1.0 + y);
            s += max(min(t, float3(1.0)) - u.uThreshold, 0.0);
        }
    }
    return float4(s / 16.0, 0.0);
}

// SimpleAutoExp's average: the scene's luminance (GlareSourcePre's weights) as the toned picture keeps it, no brighter
// than the white level at the exposure in use, over each 64x32 texel.
fragment float4 xmbGaia_lumFragment(XmbGaiaQuadVarying in [[stage_in]],
                                    constant XmbGaiaLumUniforms &u [[buffer(0)]],
                                    texture2d<float> uScene [[texture(0)]],
                                    texture2d<float> uExpTex [[texture(1)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float e = max(uExpTex.sample(linear, float2(0.5)).r, 1e-3);
    float s = 0.0;
    for (int j = 0; j < 4; j++) {
        for (int i = 0; i < 4; i++) {
            float2 o = (float2(float(i), float(j)) - 1.5) * 0.25 * u.uFoot;
            float3 c = min(uScene.sample(linear, xmbGaia_target(in.vUv + o)).rgb * e, float3(u.uWhite)) / e;
            s += dot(c, float3(0.27, 0.67, 0.06));
        }
    }
    return float4(s / 16.0, 0.0, 0.0, 1.0);
}

// The exposure eased toward KEY VALUE over the scene's average, held in MIN..MAX EXPOSURE.
fragment float4 xmbGaia_exposureFragment(XmbGaiaQuadVarying in [[stage_in]],
                                         constant XmbGaiaExposureUniforms &u [[buffer(0)]],
                                         texture2d<float> uLum [[texture(0)]],
                                         texture2d<float> uPrev [[texture(1)]]) {
    constexpr sampler levels(filter::linear, mip_filter::linear, address::clamp_to_edge);
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float avg = uLum.sample(levels, float2(0.5), level(u.uTop)).r;
    float want = clamp(u.uKey / max(avg, 1e-4), u.uRange.x, u.uRange.y);
    float prev = uPrev.sample(linear, float2(0.5)).r;
    return float4(mix(prev, want, u.uSpeed), 0.0, 0.0, 1.0);
}

fragment float4 xmbGaia_copyFragment(XmbGaiaQuadVarying in [[stage_in]],
                                     texture2d<float> uTex [[texture(0)]],
                                     constant float &uWeight [[buffer(0)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    return float4(uTex.sample(linear, xmbGaia_target(in.vUv)).rgb * uWeight, 0.0);
}

fragment float4 xmbGaia_blurFragment(XmbGaiaQuadVarying in [[stage_in]],
                                     texture2d<float> uTex [[texture(0)]],
                                     constant float2 &uStep [[buffer(0)]],
                                     constant float3 *uW [[buffer(1)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float2 uv = xmbGaia_target(in.vUv);
    float3 s = uTex.sample(linear, uv).rgb * uW[0];
    for (int k = 1; k < 8; k++) {
        float2 o = uStep * float(k);
        s += (uTex.sample(linear, uv + o).rgb + uTex.sample(linear, uv - o).rgb) * uW[k];
    }
    return float4(s, 0.0);
}

// ToneApplyDisplay: the tone (EXPOSURE, WHITE LEVEL), the glare added, GAMMA, the dither.
fragment float4 xmbGaia_outFragment(XmbGaiaQuadVarying in [[stage_in]],
                                    constant XmbGaiaOutUniforms &u [[buffer(0)]],
                                    texture2d<float> uScene [[texture(0)]],
                                    texture2d<float> uGlare [[texture(1)]],
                                    texture2d<float> uExpTex [[texture(2)]]) {
    constexpr sampler linear(filter::linear, address::clamp_to_edge);
    float exposure = u.uAuto > 0.5 ? uExpTex.sample(linear, float2(0.5)).r : u.uExposure;
    float2 uv = xmbGaia_target(in.vUv);
    float3 y = uScene.sample(linear, uv).rgb * exposure;
    float3 t = y * (1.0 + y * u.uWhiteSqrRcp) / (1.0 + y) + uGlare.sample(linear, uv).rgb;
    t = pow(max(t, 0.0), float3(1.0 / u.uGamma));
    float n = fract(sin(dot(in.position.xy + u.uSeed * 71.0, float2(12.9898, 78.233))) * 43758.5453);
    return float4(t + (n - 0.5) * u.uDither, 1.0);
}
