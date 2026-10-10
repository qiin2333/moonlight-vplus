#pragma once

#include <android/native_window.h>

#include <cstddef>
#include <cstdint>
#include <memory>

namespace FramegenPipeline {

// A decoder session which keeps PyroWave's three output planes and the final
// color conversion on one Vulkan device.  Presentation is performed through a
// Vulkan swapchain created from the Android Surface; no ANativeWindow CPU lock
// or YUV readback is used on this path.
class PyrowaveVulkanDecoder {
public:
    PyrowaveVulkanDecoder();
    ~PyrowaveVulkanDecoder();

    PyrowaveVulkanDecoder(const PyrowaveVulkanDecoder&) = delete;
    PyrowaveVulkanDecoder& operator=(const PyrowaveVulkanDecoder&) = delete;

    bool create(int width, int height, int hdrMode, bool fullRange);
    bool setSurface(ANativeWindow* window);
    bool setHdrMetadata(bool enabled, const std::uint8_t* data, std::size_t length);
    bool setDynamicHdr(int format, float targetPeakNits);
    int submit(const std::uint8_t* data, std::size_t length,
               const std::uint8_t* frameMetadata = nullptr, std::size_t metadataLength = 0);
    std::uint64_t getLastTimingsPacked();
    void destroy() noexcept;

    [[nodiscard]] static bool available(int width, int height, int hdrMode);

private:
    struct impl;
    std::unique_ptr<impl> impl_;
};

} // namespace FramegenPipeline
