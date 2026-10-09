#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace moonlight::pyrowave {

struct dynamic_hdr_scene {
    int format{0};
    float minimum_nits{0.0F};
    float maximum_nits{0.0F};
    float average_nits{0.0F};
    float median_nits{0.0F};
    float variance_pq{0.0F};
    float mastering_peak_nits{1000.0F};
    float hlg_nominal_peak_nits{0.0F};
    float mastering_minimum_nits{0.0F};
    float max_cll_nits{0.0F};
    float max_fall_nits{0.0F};
    bool scene_refresh{false};
    std::array<std::uint16_t, 4> active_area{};
};

// Parses only the desktop metadata profiles produced by Sunshine. It does
// not accept arbitrary movie RPU mappings, enhancement layers or trim blocks.
bool parse_dynamic_hdr_frame(const std::uint8_t* data, std::size_t length,
                             int format, int width, int height,
                             dynamic_hdr_scene& scene) noexcept;

float pq_to_nits(float signal) noexcept;
float nits_to_pq(float nits) noexcept;

inline constexpr std::size_t dynamic_hdr_lut_size = 256;
using dynamic_hdr_lut = std::array<float, dynamic_hdr_lut_size>;

// Application-side scene adaptation. Input/output are PQ-domain coordinates
// even when the base pixels use HLG; the shader handles that transfer separately.
bool build_dynamic_hdr_lut(const dynamic_hdr_scene& scene, float target_peak_nits,
                           dynamic_hdr_lut& lut) noexcept;

} // namespace moonlight::pyrowave
