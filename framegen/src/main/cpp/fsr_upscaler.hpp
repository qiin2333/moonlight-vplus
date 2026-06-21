#pragma once

#include <vulkan/vulkan_core.h>

#include <cstddef>
#include <cstdint>

namespace FramegenPipeline::FsrUpscaler {

constexpr float kDefaultRcasSharpnessStops = 0.25F;
constexpr uint32_t kWorkRegionDim = 16;

struct ShaderBlob {
    const uint32_t* code{nullptr};
    size_t size{0};
    const char* name{nullptr};
};

struct EasuPushConstants {
    uint32_t const0[4]{};
    uint32_t const1[4]{};
    uint32_t const2[4]{};
    uint32_t const3[4]{};
};

struct RcasPushConstants {
    uint32_t const0[4]{};
};

static_assert(sizeof(EasuPushConstants) == 64);
static_assert(sizeof(RcasPushConstants) == 16);

ShaderBlob easuShaderForIntermediateFormat(VkFormat format);
ShaderBlob rcasShaderForOutputFormat(VkFormat format);

EasuPushConstants buildEasuConstants(uint32_t srcWidth,
                                     uint32_t srcHeight,
                                     uint32_t dstWidth,
                                     uint32_t dstHeight);
RcasPushConstants buildRcasConstants(float sharpnessStops = kDefaultRcasSharpnessStops);

VkPushConstantRange pushConstantRange();
uint32_t dispatchGroupCount(uint32_t pixels);

} // namespace FramegenPipeline::FsrUpscaler
