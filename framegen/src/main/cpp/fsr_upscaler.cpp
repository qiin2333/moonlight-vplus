#include "fsr_upscaler.hpp"

#include "rgba_fsr_easu.comp.spv.h"
#include "rgba_fsr_easu16f.comp.spv.h"
#include "rgba_fsr_rcas.comp.spv.h"
#include "rgba_fsr_rcas16f.comp.spv.h"
#include "rgba_fsr_rcas10a2.comp.spv.h"

#include <algorithm>
#include <cmath>
#include <cstring>

namespace FramegenPipeline::FsrUpscaler {
namespace {

uint32_t floatBits(float value) {
    uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    return bits;
}

} // namespace

ShaderBlob easuShaderForIntermediateFormat(VkFormat format) {
    if (format == VK_FORMAT_R16G16B16A16_SFLOAT) {
        return { k_rgba_fsr_easu16f_spv, k_rgba_fsr_easu16f_spv_size, "rgba_fsr_easu16f" };
    }
    return { k_rgba_fsr_easu_spv, k_rgba_fsr_easu_spv_size, "rgba_fsr_easu" };
}

ShaderBlob rcasShaderForOutputFormat(VkFormat format) {
    if (format == VK_FORMAT_A2B10G10R10_UNORM_PACK32) {
        return { k_rgba_fsr_rcas10a2_spv, k_rgba_fsr_rcas10a2_spv_size, "rgba_fsr_rcas10a2" };
    }
    if (format == VK_FORMAT_R16G16B16A16_SFLOAT) {
        return { k_rgba_fsr_rcas16f_spv, k_rgba_fsr_rcas16f_spv_size, "rgba_fsr_rcas16f" };
    }
    return { k_rgba_fsr_rcas_spv, k_rgba_fsr_rcas_spv_size, "rgba_fsr_rcas" };
}

EasuPushConstants buildEasuConstants(uint32_t srcWidth,
                                     uint32_t srcHeight,
                                     uint32_t dstWidth,
                                     uint32_t dstHeight) {
    const float inputW = static_cast<float>(std::max(srcWidth, 1U));
    const float inputH = static_cast<float>(std::max(srcHeight, 1U));
    const float outputW = static_cast<float>(std::max(dstWidth, 1U));
    const float outputH = static_cast<float>(std::max(dstHeight, 1U));
    const float rcpInputW = 1.0F / inputW;
    const float rcpInputH = 1.0F / inputH;

    EasuPushConstants c{};
    c.const0[0] = floatBits(inputW / outputW);
    c.const0[1] = floatBits(inputH / outputH);
    c.const0[2] = floatBits((0.5F * inputW / outputW) - 0.5F);
    c.const0[3] = floatBits((0.5F * inputH / outputH) - 0.5F);
    c.const1[0] = floatBits(rcpInputW);
    c.const1[1] = floatBits(rcpInputH);
    c.const1[2] = floatBits(rcpInputW);
    c.const1[3] = floatBits(-rcpInputH);
    c.const2[0] = floatBits(-rcpInputW);
    c.const2[1] = floatBits(2.0F * rcpInputH);
    c.const2[2] = floatBits(rcpInputW);
    c.const2[3] = floatBits(2.0F * rcpInputH);
    c.const3[0] = floatBits(0.0F);
    c.const3[1] = floatBits(4.0F * rcpInputH);
    c.const3[2] = 0;
    c.const3[3] = 0;
    return c;
}

RcasPushConstants buildRcasConstants(float sharpnessStops) {
    RcasPushConstants c{};
    const float sharpness = std::exp2(-std::max(0.0F, sharpnessStops));
    c.const0[0] = floatBits(sharpness);
    return c;
}

VkPushConstantRange pushConstantRange() {
    return {
        .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT,
        .offset = 0,
        .size = static_cast<uint32_t>(sizeof(EasuPushConstants)),
    };
}

uint32_t dispatchGroupCount(uint32_t pixels) {
    return (pixels + (kWorkRegionDim - 1U)) / kWorkRegionDim;
}

} // namespace FramegenPipeline::FsrUpscaler
