R"(
#version 450

// Snapdragon Game Super Resolution 1.0, "mobile" variant.
//
// SPDX-FileCopyrightText: Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
//
// The filter body is Qualcomm's, unchanged in substance. What differs from their sample is the
// shape around it: theirs is a fragment shader over a fullscreen triangle, and this is a compute
// pass, because that is what the VK device layer already schedules (see fsr_ubershader.glsl). So
// the interpolated texcoord becomes an explicit UV computed from the invocation id, and the
// fragment output becomes an imageStore.
//
// Suggested by CamilleLaVey, who made the same filter work in Eden. Eden's glue is
// GPL-3.0-or-later and RPCS3 is GPL-2.0-only, so none of it is used here: the algorithm below is
// Qualcomm's BSD-3-Clause release, which is GPL-2.0 compatible, and the crop mapping and widened
// sharpness range are reimplemented from the description of what they do rather than copied.

#define EDGE_THRESHOLD (8.0 / 255.0)

// Floors the deviation reciprocal below. `sum` is the summed absolute deviation from the block
// mean, so it is exactly ZERO on a flat block and the divide is a 0/0. Qualcomm's own shaders do
// not guard it -- on a flat block the edgeVote above normally rejects the pixel before reaching
// here, but that is a property of the threshold, not a guarantee.
#define DEVIATION_FLOOR 6.0e-02

// Same class of guard for the direction estimate: on a flat block both deltas are zero and the
// inversesqrt is 1/0. Value is Qualcomm's own, matching sgsr_shader_edge.glsl.
#define DIRECTION_EPSILON 3.075740e-05

layout(push_constant) uniform const_buffer
{
    // Output extent, for the bounds check. A dispatch is rounded up to whole workgroups, so the
    // last one runs partly outside the image.
    uvec2 dstSize;
    // The displayed region inside the source texture, normalised. The RSX output target is
    // larger than the picture in it; without this the filter would upscale the padding too.
    vec2 uvOffset;
    vec2 uvScale;
    // Source texture dimensions and their reciprocal. Qualcomm's "size" and "scale".
    vec2 srcSize;
    vec2 invSrcSize;
    // 0..2. 1.0 is Qualcomm's own default; the range reaches 2.0 because it is too tight to be
    // useful at the top end otherwise.
    float edgeSharpness;
};

layout(set = 0, binding = 0) uniform sampler2D InputTexture;
layout(set = 0, binding = 1, rgba8) uniform writeonly image2D OutputTexture;

// Estimates the local edge direction from the left and right gathers. Qualcomm's, vectorised
// only in the sense that it reads vec4 gathers; the maths is scalar and unchanged from
// sgsr_shader_edge.glsl.
vec2 edgeDirection(vec4 left, vec4 right)
{
    float RxLz = right.x - left.z;
    float RwLy = right.w - left.y;
    vec2 delta = vec2(RxLz + RwLy, RxLz - RwLy);
    float lengthInv = inversesqrt((delta.x * delta.x + DIRECTION_EPSILON) + (delta.y * delta.y));
    return delta * lengthInv;
}

// Weights along the edge direction rather than isotropically.
//
// This is the edge-direction weighting that sgsr_shader_edge.glsl already does, brought into the
// plain variant -- which changes what the two variants are for. The edge shader takes a fourth
// gather to do it; this reuses the three the plain path already has, so the extra cost here is
// arithmetic rather than bandwidth.
//
// `c` is now the raw deviation and `std` the squared reciprocal-mean, matching Qualcomm's
// scalar weightY. The previous form took a pre-clamped `abs(dg) * std` and an isotropic
// `* 0.55 + std`; both are gone, so the std computation below had to change with it.
vec4 weightY(vec4 dx, vec4 dy, vec4 c, float std, vec2 dir)
{
    vec4 edgeDis = (dx * dir.y) + (dy * dir.x);
    vec4 x = ((dx * dx) + (dy * dy))
           + ((edgeDis * edgeDis) * ((clamp((c * c) * std, 0.0f, 1.0f) * 0.7f) - 1.0f));

    // Qualcomm's fastLanczos2, applied to four lanes at once.
    //
    // It expands to (x-1)(x-4)^3. What was here instead was
    // `(x - 1) * (x - 4) * 3.8125`, commented as an approximation of that -- but substituting
    // 3.8125 for (x-4)^2 only holds near x = 2 and diverges everywhere else, so it is a real
    // deviation from the filter rather than a cheaper spelling of it. It also appears in none of
    // Qualcomm's sources, GLSL or HLSL, so its provenance was unclear -- which matters here,
    // because the only reason this filter can ship in a GPL-2.0-only tree is that it is
    // Qualcomm's BSD-3-Clause code.
    //
    // Vectorising a scalar expression is mechanical, so this stays Qualcomm's.
    vec4 wA = x - 4.0f;
    vec4 wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

layout(local_size_x = 8, local_size_y = 8) in;
void main()
{
    const uvec2 pos = gl_GlobalInvocationID.xy;
    if (pos.x >= dstSize.x || pos.y >= dstSize.y)
        return;

    // Centre of this output pixel, mapped into the displayed region of the source.
    const vec2 texcoord = uvOffset + ((vec2(pos) + vec2(0.5f)) / vec2(dstSize)) * uvScale;

    vec4 color = textureLod(InputTexture, texcoord, 0.0f);

    // image coord
    vec2 icoord = (texcoord * srcSize + vec2(-0.5f, 0.5f));
    vec2 icoord_pixel = floor(icoord);
    vec2 coord = icoord_pixel * invSrcSize;
    vec2 pl = icoord - icoord_pixel;
    // left: 0, right: 1, upDown: 2
    mat3x4 dg = mat3x4(
        textureGather(InputTexture, coord, 1),
        textureGather(InputTexture, coord + vec2(2.f * invSrcSize.x, 0.0f), 1),
        vec4(
            textureGather(InputTexture, coord + vec2(invSrcSize.x, -invSrcSize.y), 1).wz,
            textureGather(InputTexture, coord + vec2(invSrcSize.x, +invSrcSize.y), 1).yx
        )
    );
    float edgeVote = abs(dg[0].z - dg[0].y) + abs(color.y - dg[0].y) + abs(color.y - dg[0].z);
    if (edgeVote > EDGE_THRESHOLD)
    {
        float mean = (dg[0].y + dg[0].z + dg[1].x + dg[1].w) * 0.25f;
        dg = dg - mean;
        vec4 sum = abs(dg[0]) + abs(dg[1]) + abs(dg[2]);

        // Qualcomm's constant and squaring, from their scalar shader. The 2.181818 that was here
        // belongs to the isotropic weightY that has just been replaced, and carrying it over
        // would scale the new edge term wrongly rather than merely differently.
        float sumMean = 1.014185e+01f / max(sum.x + sum.y + sum.z + sum.w, DEVIATION_FLOOR);
        float std = sumMean * sumMean;
        vec2 dir = edgeDirection(dg[0], dg[1]);
        mat2x4 w = mat2x4(
            weightY(
                pl.xxxx + vec4(+1.0f, +0.0f, +0.0f, +1.0f),
                pl.yyyy + vec4(-1.0f, -1.0f, +0.0f, +0.0f),
                dg[0], std, dir
            ) + weightY(
                pl.xxxx + vec4(-1.0f, -2.0f, -2.0f, -1.0f),
                pl.yyyy + vec4(-1.0f, -1.0f, +0.0f, +0.0f),
                dg[1], std, dir
            ) + weightY(
                pl.xxxx + vec4(+0.0f, -1.0f, -1.0f, +0.0f),
                pl.yyyy + vec4(+1.0f, +1.0f, -2.0f, -2.0f),
                dg[2], std, dir
            ),
            dg[0] + dg[1] + dg[2]
        );
        // compute final y with bounds
        vec2 yb = vec2(
            min(min(dg[0].y, dg[0].z), min(dg[1].x, dg[1].w)), // min
            max(max(dg[0].y, dg[0].z), max(dg[1].x, dg[1].w))  // max
        );
        vec2 fvy = vec2(
            w[0].x + w[0].y + w[0].z + w[0].w,
            w[1].x + w[1].y + w[1].z + w[1].w
        );
        float fy = clamp((fvy.y / fvy.x) * edgeSharpness, yb[0], yb[1]);
        // Smooth high contrast input
        float dy = clamp(fy - color.y + mean, -23.0f / 255.0f, 23.0f / 255.0f);
        color = clamp(color + dy, 0.0f, 1.0f);
    }
    color.w = 1.0f; // assume alpha channel is not used
    imageStore(OutputTexture, ivec2(pos), color);
}
)"
