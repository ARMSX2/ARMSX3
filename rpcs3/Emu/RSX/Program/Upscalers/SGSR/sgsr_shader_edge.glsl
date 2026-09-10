R"(
#version 450

// Snapdragon Game Super Resolution 1.0, "mobile" variant with edge direction.
//
// SPDX-FileCopyrightText: Copyright (c) 2025, Qualcomm Innovation Center, Inc. All rights reserved.
// SPDX-License-Identifier: BSD-3-Clause
//
// Qualcomm ship one file with a UseEdgeDirection define; this is that path, taken from
// sgsr1_shader_mobile_edge_direction.frag in their own repository rather than from any
// downstream port. Higher quality than the plain variant and slightly more expensive: it
// estimates the local edge direction and weights along it instead of isotropically.
//
// Shaped for compute the same way the plain variant is -- the interpolated texcoord becomes a UV
// from the invocation id, ViewportInfo becomes our push constants, and the fragment output
// becomes an imageStore. The filter body is otherwise theirs, kept scalar exactly as they wrote
// it (twelve weightY calls) rather than re-vectorised, so it stays recognisably their code.

#define OperationMode 1
#define EdgeThreshold (8.0 / 255.0)

layout(push_constant) uniform const_buffer
{
    uvec2 dstSize;
    vec2 uvOffset;
    vec2 uvScale;
    vec2 srcSize;
    vec2 invSrcSize;
    float edgeSharpness;
};

layout(set = 0, binding = 0) uniform sampler2D InputTexture;
layout(set = 0, binding = 1, rgba8) uniform writeonly image2D OutputTexture;

float fastLanczos2(float x)
{
    float wA = x - 4.0f;
    float wB = x * wA - wA;
    wA *= wA;
    return wB * wA;
}

vec2 weightY(float dx, float dy, float c, vec3 data)
{
    float std = data.x;
    vec2 dir = data.yz;

    float edgeDis = ((dx * dir.y) + (dy * dir.x));
    float x = (((dx * dx) + (dy * dy)) + ((edgeDis * edgeDis) * ((clamp(((c * c) * std), 0.0f, 1.0f) * 0.7f) + -1.0f)));

    float w = fastLanczos2(x);
    return vec2(w, w * c);
}

vec2 edgeDirection(vec4 left, vec4 right)
{
    vec2 dir;
    float RxLz = (right.x + (-left.z));
    float RwLy = (right.w + (-left.y));
    vec2 delta;
    delta.x = (RxLz + RwLy);
    delta.y = (RxLz + (-RwLy));
    float lengthInv = inversesqrt((delta.x * delta.x + 3.075740e-05f) + (delta.y * delta.y));
    dir.x = (delta.x * lengthInv);
    dir.y = (delta.y * lengthInv);
    return dir;
}

layout(local_size_x = 8, local_size_y = 8) in;
void main()
{
    const uvec2 pos = gl_GlobalInvocationID.xy;
    if (pos.x >= dstSize.x || pos.y >= dstSize.y)
        return;

    // Centre of this output pixel, mapped into the displayed region of the source.
    const vec2 texcoord = uvOffset + ((vec2(pos) + vec2(0.5f)) / vec2(dstSize)) * uvScale;

    vec4 color;
    color.xyz = textureLod(InputTexture, texcoord, 0.0f).xyz;

    vec2 imgCoord = ((texcoord * srcSize) + vec2(-0.5f, 0.5f));
    vec2 imgCoordPixel = floor(imgCoord);
    vec2 coord = (imgCoordPixel * invSrcSize);
    vec2 pl = (imgCoord + (-imgCoordPixel));
    vec4 left = textureGather(InputTexture, coord, OperationMode);

    float edgeVote = abs(left.z - left.y) + abs(color[OperationMode] - left.y) + abs(color[OperationMode] - left.z);
    if (edgeVote > EdgeThreshold)
    {
        coord.x += invSrcSize.x;

        vec4 right = textureGather(InputTexture, coord + vec2(invSrcSize.x, 0.0f), OperationMode);
        vec4 upDown;
        upDown.xy = textureGather(InputTexture, coord + vec2(0.0f, -invSrcSize.y), OperationMode).wz;
        upDown.zw = textureGather(InputTexture, coord + vec2(0.0f, invSrcSize.y), OperationMode).yx;

        float mean = (left.y + left.z + right.x + right.w) * 0.25f;
        left = left - vec4(mean);
        right = right - vec4(mean);
        upDown = upDown - vec4(mean);
        color.w = color[OperationMode] - mean;

        float sum = (((((abs(left.x) + abs(left.y)) + abs(left.z)) + abs(left.w)) + (((abs(right.x) + abs(right.y)) + abs(right.z)) + abs(right.w))) + (((abs(upDown.x) + abs(upDown.y)) + abs(upDown.z)) + abs(upDown.w)));
        // Same unguarded 0/0 as the plain variant had: sum is zero on a flat block.
        float sumMean = 1.014185e+01f / max(sum, 6.0e-02f);
        float std = (sumMean * sumMean);

        vec3 data = vec3(std, edgeDirection(left, right));

        vec2 aWY = weightY(pl.x, pl.y + 1.0f, upDown.x, data);
        aWY += weightY(pl.x - 1.0f, pl.y + 1.0f, upDown.y, data);
        aWY += weightY(pl.x - 1.0f, pl.y - 2.0f, upDown.z, data);
        aWY += weightY(pl.x, pl.y - 2.0f, upDown.w, data);
        aWY += weightY(pl.x + 1.0f, pl.y - 1.0f, left.x, data);
        aWY += weightY(pl.x, pl.y - 1.0f, left.y, data);
        aWY += weightY(pl.x, pl.y, left.z, data);
        aWY += weightY(pl.x + 1.0f, pl.y, left.w, data);
        aWY += weightY(pl.x - 1.0f, pl.y - 1.0f, right.x, data);
        aWY += weightY(pl.x - 2.0f, pl.y - 1.0f, right.y, data);
        aWY += weightY(pl.x - 2.0f, pl.y, right.z, data);
        aWY += weightY(pl.x - 1.0f, pl.y, right.w, data);

        float finalY = aWY.y / aWY.x;
        float maxY = max(max(left.y, left.z), max(right.x, right.w));
        float minY = min(min(left.y, left.z), min(right.x, right.w));
        // Qualcomm hardcode EdgeSharpness 2.0 here; ours comes from the slider.
        float deltaY = clamp(edgeSharpness * finalY, minY, maxY) - color.w;

        // smooth high contrast input
        deltaY = clamp(deltaY, -23.0f / 255.0f, 23.0f / 255.0f);

        color.x = clamp((color.x + deltaY), 0.0f, 1.0f);
        color.y = clamp((color.y + deltaY), 0.0f, 1.0f);
        color.z = clamp((color.z + deltaY), 0.0f, 1.0f);
    }

    color.w = 1.0f; // assume alpha channel is not used
    imageStore(OutputTexture, ivec2(pos), color);
}
)"
