#extension GL_GOOGLE_include_directive : require

layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;

layout(set = 0, binding = 0) uniform sampler2D uSrc;
layout(set = 0, binding = 1, FSR_OUTPUT_FORMAT) uniform writeonly image2D uDst;

layout(push_constant) uniform FsrConstants {
    uvec4 Const0;
    uvec4 Const1;
    uvec4 Const2;
    uvec4 Const3;
} pc;

#define A_GPU 1
#define A_GLSL 1
#include "amd_fsr/ffx_a.h"

#define FSR_EASU_F 1
AF4 FsrEasuRF(AF2 p) { return textureGather(uSrc, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(uSrc, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(uSrc, p, 2); }

#include "amd_fsr/ffx_fsr1.h"

void runEasu(AU2 pos) {
    ivec2 dstSize = imageSize(uDst);
    if (pos.x >= uint(dstSize.x) || pos.y >= uint(dstSize.y)) {
        return;
    }

    AF3 c;
    FsrEasuF(c, pos, pc.Const0, pc.Const1, pc.Const2, pc.Const3);
    imageStore(uDst, ASU2(pos), AF4(max(c, AF3(0.0, 0.0, 0.0)), 1.0));
}

void main() {
    AU2 gxy = ARmp8x8(gl_LocalInvocationID.x) +
              AU2(gl_WorkGroupID.x << 4u, gl_WorkGroupID.y << 4u);
    runEasu(gxy);
    gxy.x += 8u;
    runEasu(gxy);
    gxy.y += 8u;
    runEasu(gxy);
    gxy.x -= 8u;
    runEasu(gxy);
}
