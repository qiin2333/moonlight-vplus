#include "pyrowave_hdr_presentation.hpp"

#include <array>
#include <cmath>
#include <cstdio>
#include <limits>
#include <type_traits>
#include <vector>

#define CHECK(condition) do { if (!(condition)) { \
    std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); return 1; \
} } while (0)

namespace {
struct submitted_metadata {
    unsigned calls{};
    VkDevice device{};
    VkSwapchainKHR swapchain{};
    VkHdrMetadataEXT metadata{};
    bool vivid_chain_valid{};
    std::vector<std::uint8_t> vivid;
} submitted;

void VKAPI_CALL capture_metadata(VkDevice device, std::uint32_t count,
                                const VkSwapchainKHR* swapchains, const VkHdrMetadataEXT* metadata) {
    ++submitted.calls;
    if (count != 1 || swapchains == nullptr || metadata == nullptr) return;
    submitted.device = device;
    submitted.swapchain = *swapchains;
    submitted.metadata = *metadata;
    submitted.metadata.pNext = nullptr;
    submitted.vivid.clear();
    submitted.vivid_chain_valid = false;
    if (metadata->pNext != nullptr) {
        const auto* vivid = static_cast<const VkHdrVividDynamicMetadataHUAWEI*>(metadata->pNext);
        submitted.vivid_chain_valid = vivid->sType == VK_STRUCTURE_TYPE_HDR_VIVID_DYNAMIC_METADATA_HUAWEI &&
            vivid->pNext == nullptr;
        const auto* data = static_cast<const std::uint8_t*>(vivid->pDynamicMetadata);
        submitted.vivid.assign(data, data + vivid->dynamicMetadataSize);
    }
}

template<typename T> T handle(std::uintptr_t value) {
    if constexpr (std::is_pointer_v<T>) return reinterpret_cast<T>(value);
    else return static_cast<T>(value);
}

std::array<std::uint8_t, 26> payload(std::uint16_t peak = 1000, std::uint16_t cll = 1200,
                                    std::uint16_t fall = 400) {
    const std::array<std::uint16_t, 13> fields{
        35400, 14600, 8500, 39850, 6550, 2300, 15635, 16450, peak, 1, cll, fall, 600};
    std::array<std::uint8_t, 26> bytes{};
    for (std::size_t i = 0; i < fields.size(); ++i) {
        bytes[2 * i] = static_cast<std::uint8_t>(fields[i]);
        bytes[2 * i + 1] = static_cast<std::uint8_t>(fields[i] >> 8);
    }
    return bytes;
}
} // namespace

int main() {
    using namespace moonlight::pyrowave;
    const auto bytes = payload();
    hdr_static_snapshot snapshot;
    CHECK(snapshot.update(true, bytes.data(), bytes.size()) == hdr_snapshot_update::accepted);
    const auto source = snapshot.metadata;
    CHECK(snapshot.enabled && snapshot.valid);
    CHECK(std::abs(source.display_primaries[0].x - 0.708F) < 0.00001F);
    CHECK(std::abs(source.white_point.x - 0.3127F) < 0.00001F);
    CHECK(std::abs(source.min_display_luminance - 0.0001F) < 0.000001F);
    CHECK(source.max_full_frame_luminance == 600);

    // The production parser must never partially replace an accepted output.
    auto parsed = source;
    auto corrupt = bytes;
    corrupt[0] = 0x51;
    corrupt[1] = 0xC3; // 50001, outside Sunshine's normalized coordinate range.
    CHECK(!parse_ss_hdr_metadata(corrupt.data(), corrupt.size(), parsed));
    CHECK(parsed.max_display_luminance == source.max_display_luminance);
    CHECK(parsed.display_primaries[0].x == source.display_primaries[0].x);
    for (std::size_t size = 0; size < bytes.size(); ++size) {
        CHECK(!parse_ss_hdr_metadata(bytes.data(), size, parsed));
    }
    const auto zero_peak = payload(0);
    CHECK(snapshot.update(true, zero_peak.data(), zero_peak.size()) == hdr_snapshot_update::invalid);
    CHECK(snapshot.update(true, corrupt.data(), corrupt.size()) == hdr_snapshot_update::invalid);
    CHECK(snapshot.update(true, nullptr, 0) == hdr_snapshot_update::missing);
    std::array<std::uint8_t, 26> zeros{};
    CHECK(snapshot.update(true, zeros.data(), zeros.size()) == hdr_snapshot_update::missing);
    CHECK(snapshot.valid && snapshot.metadata.max_display_luminance == 1000);

    const auto device = handle<VkDevice>(1);
    const auto first = handle<VkSwapchainKHR>(2);
    const auto rebuilt = handle<VkSwapchainKHR>(3);
    CHECK(apply_swapchain_hdr_metadata(device, first, capture_metadata, snapshot.metadata));
    CHECK(submitted.calls == 1 && submitted.device == device && submitted.swapchain == first);
    CHECK(submitted.metadata.sType == VK_STRUCTURE_TYPE_HDR_METADATA_EXT);
    CHECK(submitted.metadata.displayPrimaryRed.x == source.display_primaries[0].x);
    CHECK(submitted.metadata.displayPrimaryGreen.y == source.display_primaries[1].y);
    CHECK(submitted.metadata.displayPrimaryBlue.x == source.display_primaries[2].x);
    CHECK(submitted.metadata.whitePoint.y == source.white_point.y);
    CHECK(submitted.metadata.maxLuminance == 1000 && submitted.metadata.minLuminance == source.min_display_luminance);
    CHECK(submitted.metadata.maxContentLightLevel == 1200 && submitted.metadata.maxFrameAverageLightLevel == 400);
    CHECK(!submitted.vivid_chain_valid);

    // Rebind/rebuild uses the same source snapshot, but never the old handle.
    CHECK(apply_swapchain_hdr_metadata(device, rebuilt, capture_metadata, snapshot.metadata));
    CHECK(submitted.swapchain == rebuilt && snapshot.metadata.max_full_frame_luminance == 600);
    const auto updated = payload(800, 900, 300);
    CHECK(snapshot.update(true, updated.data(), updated.size()) == hdr_snapshot_update::accepted);
    CHECK(apply_swapchain_hdr_metadata(device, rebuilt, capture_metadata, snapshot.metadata));
    CHECK(submitted.swapchain == rebuilt && submitted.metadata.maxLuminance == 800);
    CHECK(submitted.metadata.maxFrameAverageLightLevel == 300);

    CHECK(apply_swapchain_hdr_metadata(device, rebuilt, capture_metadata, snapshot.metadata));
    CHECK(submitted.metadata.maxLuminance == 800 && submitted.metadata.maxContentLightLevel == 900);
    CHECK(submitted.metadata.maxFrameAverageLightLevel == 300 && snapshot.metadata.max_display_luminance == 800);
    const auto unknown_cll = payload(1000, 0, 0);
    CHECK(snapshot.update(true, unknown_cll.data(), unknown_cll.size()) == hdr_snapshot_update::accepted);
    CHECK(apply_swapchain_hdr_metadata(device, rebuilt, capture_metadata, snapshot.metadata));
    CHECK(submitted.metadata.maxContentLightLevel == 0 && submitted.metadata.maxFrameAverageLightLevel == 0);

    const std::array<std::uint8_t, 8> vivid{1, 0, 1, 0, 2, 0, 3, 0};
    CHECK(apply_swapchain_hdr_metadata(device, rebuilt, capture_metadata, snapshot.metadata,
        vivid.data(), vivid.size()));
    CHECK(submitted.vivid_chain_valid && submitted.vivid == std::vector<std::uint8_t>(vivid.begin(), vivid.end()));
    CHECK(submitted.metadata.maxLuminance == 1000);
    CHECK(select_dynamic_hdr_presentation(true, true, true, snapshot) == dynamic_hdr_presentation::native_vivid);
    CHECK(select_dynamic_hdr_presentation(true, false, true, snapshot) == dynamic_hdr_presentation::unsupported);
    CHECK(select_dynamic_hdr_presentation(true, true, false, snapshot) == dynamic_hdr_presentation::unsupported);
    CHECK(select_dynamic_hdr_presentation(false, true, true, snapshot) == dynamic_hdr_presentation::application_mapped);
    CHECK(apply_swapchain_hdr_metadata(device, rebuilt, capture_metadata, snapshot.metadata,
        true, 500.0F));
    CHECK(submitted.metadata.maxLuminance == 500.0F);
    CHECK(submitted.metadata.maxContentLightLevel == 0.0F);
    CHECK(submitted.metadata.maxFrameAverageLightLevel == 0.0F);
    const auto call_count = submitted.calls;
    CHECK(!apply_swapchain_hdr_metadata(device, rebuilt, nullptr, snapshot.metadata));
    CHECK(!apply_swapchain_hdr_metadata(device, VK_NULL_HANDLE, capture_metadata, snapshot.metadata));
    for (const auto invalid : {-1.0F, 0.0F, std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()}) {
        auto bad_source = source;
        bad_source.max_display_luminance = invalid;
        CHECK(!valid_hdr_static_metadata(bad_source, true));
    }
    auto bad_source = source;
    bad_source.min_display_luminance = -1;
    CHECK(!valid_hdr_static_metadata(bad_source, true));
    bad_source = source;
    bad_source.white_point.x = std::numeric_limits<float>::quiet_NaN();
    CHECK(!valid_hdr_static_metadata(bad_source, true));
    for (const auto invalid : {-1.0F, std::numeric_limits<float>::infinity(), std::numeric_limits<float>::quiet_NaN()}) {
        bad_source = source;
        bad_source.max_content_light_level = invalid;
        CHECK(!valid_hdr_static_metadata(bad_source, true));
        bad_source = source;
        bad_source.max_frame_average_light_level = invalid;
        CHECK(!valid_hdr_static_metadata(bad_source, true));
        bad_source = source;
        bad_source.max_full_frame_luminance = invalid;
        CHECK(!valid_hdr_static_metadata(bad_source, true));
    }
    CHECK(submitted.calls == call_count);
    CHECK(snapshot.update(false, nullptr, 0) == hdr_snapshot_update::cleared);
    CHECK(!snapshot.enabled && !snapshot.valid && snapshot.metadata.max_display_luminance == 0);
    CHECK(select_dynamic_hdr_presentation(true, true, true, snapshot) == dynamic_hdr_presentation::unsupported);
    CHECK(snapshot.update(true, nullptr, 0) == hdr_snapshot_update::missing && !snapshot.valid);
    std::puts("PASS: production SS_HDR_METADATA parser, transactional snapshots and live swapchain HDR submission");
    return 0;
}
