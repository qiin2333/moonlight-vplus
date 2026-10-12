#pragma once

#include "pyrowave_hdr_metadata.h"

#include <vulkan/vulkan_core.h>
#include <algorithm>
#include <cmath>

namespace moonlight::pyrowave {

enum class dynamic_hdr_presentation { pending, unsupported, native_vivid, application_mapped };

inline dynamic_hdr_presentation select_dynamic_hdr_presentation(
    bool vivid_selected, bool vivid_feature_enabled, bool metadata_entry_available,
    const hdr_static_snapshot& snapshot) noexcept {
    if (vivid_selected && vivid_feature_enabled && metadata_entry_available && snapshot.enabled && snapshot.valid) {
        return dynamic_hdr_presentation::native_vivid;
    }
    if (!vivid_selected && snapshot.enabled && snapshot.valid) {
        return dynamic_hdr_presentation::application_mapped;
    }
    return dynamic_hdr_presentation::unsupported;
}

inline bool apply_swapchain_hdr_metadata_impl(VkDevice device, VkSwapchainKHR swapchain,
                                              PFN_vkSetHdrMetadataEXT set_metadata,
                                              const hdr_static_metadata& source,
                                              bool application_mapped,
                                              float target_peak_nits,
                                              const std::uint8_t* vivid,
                                              std::size_t vivid_size) noexcept {
    if (device == VK_NULL_HANDLE || swapchain == VK_NULL_HANDLE || set_metadata == nullptr ||
        !valid_hdr_static_metadata(source, true) ||
        (vivid == nullptr) != (vivid_size == 0)) return false;

    VkHdrMetadataEXT metadata{};
    metadata.sType = VK_STRUCTURE_TYPE_HDR_METADATA_EXT;
    metadata.displayPrimaryRed = {source.display_primaries[0].x, source.display_primaries[0].y};
    metadata.displayPrimaryGreen = {source.display_primaries[1].x, source.display_primaries[1].y};
    metadata.displayPrimaryBlue = {source.display_primaries[2].x, source.display_primaries[2].y};
    metadata.whitePoint = {source.white_point.x, source.white_point.y};
    metadata.maxLuminance = source.max_display_luminance;
    metadata.minLuminance = source.min_display_luminance;
    metadata.maxContentLightLevel = source.max_content_light_level;
    metadata.maxFrameAverageLightLevel = source.max_frame_average_light_level;
    if (application_mapped && std::isfinite(target_peak_nits) && target_peak_nits >= 1.0F) {
        const auto output_peak = std::min(source.max_display_luminance, target_peak_nits);
        metadata.maxLuminance = output_peak;
        metadata.minLuminance = std::min(metadata.minLuminance, output_peak);
        metadata.maxContentLightLevel = std::min(metadata.maxContentLightLevel, output_peak);
        metadata.maxFrameAverageLightLevel = std::min(metadata.maxFrameAverageLightLevel, output_peak);
    }
    // Vulkan has no separate maxFullFrameLuminance field. Preserve it in source;
    // MaxFALL must remain the host's content-light value.
    VkHdrVividDynamicMetadataHUAWEI dynamic{};
    if (vivid_size != 0) {
        dynamic.sType = VK_STRUCTURE_TYPE_HDR_VIVID_DYNAMIC_METADATA_HUAWEI;
        dynamic.dynamicMetadataSize = vivid_size;
        dynamic.pDynamicMetadata = vivid;
        metadata.pNext = &dynamic;
    }
    set_metadata(device, 1, &swapchain, &metadata);
    return true;
}

inline bool apply_swapchain_hdr_metadata(VkDevice device, VkSwapchainKHR swapchain,
                                         PFN_vkSetHdrMetadataEXT set_metadata,
                                         const hdr_static_metadata& source,
                                         const std::uint8_t* vivid = nullptr,
                                         std::size_t vivid_size = 0) noexcept {
    return apply_swapchain_hdr_metadata_impl(device, swapchain, set_metadata, source,
                                             false, 0.0F, vivid, vivid_size);
}

inline bool apply_swapchain_hdr_metadata(VkDevice device, VkSwapchainKHR swapchain,
                                         PFN_vkSetHdrMetadataEXT set_metadata,
                                         const hdr_static_metadata& source,
                                         bool application_mapped, float target_peak_nits,
                                         const std::uint8_t* vivid = nullptr,
                                         std::size_t vivid_size = 0) noexcept {
    return apply_swapchain_hdr_metadata_impl(device, swapchain, set_metadata, source,
                                             application_mapped, target_peak_nits, vivid, vivid_size);
}

} // namespace moonlight::pyrowave
