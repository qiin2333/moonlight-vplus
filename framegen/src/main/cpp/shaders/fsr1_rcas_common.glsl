#extension GL_GOOGLE_include_directive : require

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(set = 0, binding = 0) uniform sampler2D uSrc;
layout(set = 0, binding = 1, FSR_OUTPUT_FORMAT) uniform writeonly image2D uDst;

layout(push_constant) uniform FsrConstants {
    uvec4 Const0;
} pc;

#define A_GPU 1
#define A_GLSL 1
#include "amd_fsr/ffx_a.h"

#define FSR_RCAS_F 1
AF4 FsrRcasLoadF(ASU2 p) {
    ivec2 srcSize = textureSize(uSrc, 0);
    ivec2 clamped = clamp(p, ivec2(0), srcSize - ivec2(1));
    return texelFetch(uSrc, clamped, 0);
}

void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}

#include "amd_fsr/ffx_fsr1.h"

vec3 finishRcasColor(vec3 c) {
#if FSR_CLAMP_OUTPUT
    return clamp(c, vec3(0.0), vec3(1.0));
#else
    return max(c, vec3(0.0));
#endif
}

void runRcas(AU2 pos) {
    ivec2 dstSize = imageSize(uDst);
    if (pos.x >= uint(dstSize.x) || pos.y >= uint(dstSize.y)) {
        return;
    }

    AF3 c;
    FsrRcasF(c.r, c.g, c.b, pos, pc.Const0);
    imageStore(uDst, ASU2(pos), AF4(finishRcasColor(c), 1.0));
}

void main() {
    AU2 gxy = ARmp8x8(gl_LocalInvocationID.x) +
              AU2(gl_WorkGroupID.x << 4u, gl_WorkGroupID.y << 4u);
    runRcas(gxy);
    gxy.x += 8u;
    runRcas(gxy);
    gxy.y += 8u;
    runRcas(gxy);
    gxy.x -= 8u;
    runRcas(gxy);
}
