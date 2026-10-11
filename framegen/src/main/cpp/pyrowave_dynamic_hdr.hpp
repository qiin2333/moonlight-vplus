#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace moonlight::pyrowave {

struct hdr10plus_curve {
    bool valid{false};
    float knee_x{0.0F};
    float knee_y{0.0F};
    std::array<float, 15> anchors{};
    std::size_t anchor_count{0};
    float saturation_weight{1.0F};
};

struct vivid_base_curve {
    bool valid{false};
    float target_pq{0.0F};
    float m_p{1.0F};
    float m_m{2.4F};
    float m_a{1.0F};
    float m_b{0.0F};
    float m_n{1.0F};
    std::uint8_t k1{1};
    std::uint8_t k2{1};
    std::uint8_t k3{1};
    std::uint8_t delta_mode{0};
    float delta{0.0F};
};

struct dolby_reshape_component {
    static constexpr std::size_t max_pieces = 8;
    bool valid{false};
    std::array<float, 9> pivots{};
    std::size_t pivot_count{0};
    std::array<std::uint8_t, max_pieces> mapping_idc{};
    std::array<std::uint8_t, max_pieces> poly_order{};
    std::array<std::array<float, 3>, max_pieces> poly_coefficients{};
    std::array<std::uint8_t, max_pieces> mmr_order{};
    std::array<float, max_pieces> mmr_constant{};
    std::array<std::array<std::array<float, 7>, 3>, max_pieces> mmr_coefficients{};
};

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
    hdr10plus_curve hdr10plus{};
    vivid_base_curve vivid_base{};
    std::array<dolby_reshape_component, 3> dolby_reshape{};
    std::array<std::int16_t, 9> dolby_ycc_to_rgb{};
    std::array<std::uint32_t, 3> dolby_ycc_offsets{};
    bool dolby_matrix_valid{false};
    std::array<std::int16_t, 9> dolby_rgb_to_lms{};
    bool dolby_lms_matrix_valid{false};
    bool dolby_trim_valid{false};
    std::uint16_t dolby_trim_target_pq{0};
    std::array<std::uint16_t, 5> dolby_trim_values{};
};

// Parses the desktop metadata profiles produced by Sunshine. Dolby polynomial
// and MMR reshape pieces plus CM2.9 L1/L2/L5/L6 fields are accepted when they
// are present; enhancement layers, NLQ and unsupported profiles are rejected.
struct dynamic_hdr_payload {
    const std::uint8_t* data{nullptr};
    std::size_t size{0};
};

// The optional Vivid view is borrowed only until this frame's submit returns.
// It starts at system_start_code, excluding the five-byte T.35 envelope.
bool parse_dynamic_hdr_frame(const std::uint8_t* data, std::size_t length,
                             int format, int width, int height,
                             dynamic_hdr_scene& scene,
                             dynamic_hdr_payload* vivid = nullptr) noexcept;

float pq_to_nits(float signal) noexcept;
float nits_to_pq(float nits) noexcept;

inline constexpr std::size_t dynamic_hdr_lut_size = 256;
using dynamic_hdr_lut = std::array<float, dynamic_hdr_lut_size>;

// std430-compatible presentation parameters. The matrices and Dolby reshape
// coefficients are normalized by the producer before upload; the shader never
// interprets fixed-point RPU fields directly.
struct dynamic_hdr_mapping {
    struct dolby_component {
        std::array<float, 9> pivots{};
        std::array<float, 3> padding{};
        std::array<std::array<float, 4>, dolby_reshape_component::max_pieces> piece_info{};
        std::array<std::array<std::array<float, 4>, 3>, dolby_reshape_component::max_pieces>
            polynomial{};
        std::array<std::array<std::array<std::array<float, 4>, 2>, 3>,
                   dolby_reshape_component::max_pieces>
            mmr{};
    };

    dynamic_hdr_lut pq_mapping{};
    std::array<dolby_component, 3> dolby{};
    std::array<std::array<float, 4>, 3> ycc_to_rgb{};
    std::array<float, 4> ycc_offsets{};
    std::array<std::array<float, 4>, 3> lms_to_rgb{};
    std::array<float, 4> mapping_flags{};
};

// Application-side scene adaptation. Input/output are PQ-domain coordinates
// even when the base pixels use HLG; the shader handles that transfer separately.
bool build_dynamic_hdr_lut(const dynamic_hdr_scene& scene, float target_peak_nits,
                           dynamic_hdr_lut& lut) noexcept;

bool build_dynamic_hdr_mapping(const dynamic_hdr_scene& scene, float target_peak_nits,
                               dynamic_hdr_mapping& mapping) noexcept;

} // namespace moonlight::pyrowave
