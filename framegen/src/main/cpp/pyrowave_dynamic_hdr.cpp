#include "pyrowave_dynamic_hdr.hpp"

#include "moonlight-common-c/src/DynamicHdr.h"
#include "moonlight-common-c/src/PyrowaveProtocol.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>

namespace moonlight::pyrowave {
namespace {

class bits {
public:
    bits(const std::uint8_t* bytes, std::size_t length) : data_(bytes), size_(length * 8) {}
    std::uint32_t get(unsigned count) {
        if (count > 32 || position_ > size_ || count > size_ - position_) {
            valid_ = false;
            return 0;
        }
        std::uint32_t value = 0;
        for (unsigned i = 0; i < count; ++i, ++position_) {
            value = (value << 1) | ((data_[position_ / 8] >> (7 - position_ % 8)) & 1U);
        }
        return value;
    }
    std::uint32_t ue() {
        unsigned zeros = 0;
        while (valid_ && get(1) == 0) {
            if (++zeros > 30) {
                valid_ = false;
                return 0;
            }
        }
        return valid_ ? ((1U << zeros) - 1U) + get(zeros) : 0;
    }
    std::int32_t se() {
        const auto value = ue();
        return (value & 1U) != 0 ? static_cast<std::int32_t>((value + 1U) / 2U)
                                : -static_cast<std::int32_t>(value / 2U);
    }
    bool expect(unsigned count, std::uint32_t value) { return get(count) == value && valid_; }
    bool expect_ue(std::uint32_t value) { return ue() == value && valid_; }
    bool align() {
        const auto padding = static_cast<unsigned>((8 - position_ % 8) % 8);
        return expect(padding, 0);
    }
    bool finish() {
        return valid_ && position_ <= size_ && size_ - position_ < 8 &&
               expect(static_cast<unsigned>(size_ - position_), 0);
    }
    bool valid() const { return valid_; }

private:
    const std::uint8_t* data_;
    std::size_t size_;
    std::size_t position_{0};
    bool valid_{true};
};

std::uint32_t be32(const std::uint8_t* data) {
    return (std::uint32_t(data[0]) << 24) | (std::uint32_t(data[1]) << 16) |
           (std::uint32_t(data[2]) << 8) | data[3];
}

std::uint16_t be16(const std::uint8_t* data) {
    return static_cast<std::uint16_t>((data[0] << 8) | data[1]);
}

bool prefix(const std::uint8_t* data, std::size_t size, const std::array<std::uint8_t, 6>& expected) {
    return size > expected.size() && std::memcmp(data, expected.data(), expected.size()) == 0;
}

bool parse_hdr10plus(const std::uint8_t* data, std::size_t size, dynamic_hdr_scene& scene) {
    if (size > 1024 || !prefix(data, size, {0xB5, 0, 0x3C, 0, 1, 4})) return false;
    bits reader(data + 6, size - 6);
    if (!reader.expect(8, 1) || !reader.expect(2, 1)) return false;
    const auto target_peak = reader.get(27);
    if (target_peak == 0 || target_peak > 10000 || !reader.expect(1, 0)) return false;
    float peak = 0.0F;
    for (unsigned i = 0; i < 3; ++i) {
        const auto component = reader.get(17);
        if (component > 100000) return false;
        peak = std::max(peak, component * 0.1F);
    }
    const auto average = reader.get(17);
    if (average > 100000 || !reader.expect(4, 9)) return false;
    constexpr std::array<unsigned, 9> percentages{1, 5, 10, 25, 50, 75, 90, 95, 99};
    float previous = 0.0F;
    float low = 0.0F;
    float high = 0.0F;
    for (const auto percentage : percentages) {
        if (!reader.expect(7, percentage)) return false;
        const auto value = reader.get(17);
        if (value > 100000) return false;
        if (percentage == 5) {
            if (value != 0) return false;
            continue;
        }
        if (percentage == 10) {
            if (value != 255) return false;
            continue;
        }
        const auto nits = value * 0.1F;
        if (nits < previous) return false;
        previous = nits;
        peak = std::max(peak, nits);
        if (percentage == 1) scene.minimum_nits = nits;
        if (percentage == 25) low = nits;
        if (percentage == 50) scene.median_nits = nits;
        if (percentage == 90) high = nits;
    }
    if (!reader.expect(10, 0)) return false; // fraction_bright_pixels
    if (!reader.expect(1, 0)) return false; // mastering display actual peak flag
    for (int window = 0; window < 1; ++window) {
        if (!reader.valid()) return false;
        const auto tone_mapping = reader.get(1);
        if (tone_mapping != 0) {
            const auto knee_x = reader.get(12);
            const auto knee_y = reader.get(12);
            const auto anchors = reader.get(4);
            if (!reader.valid() || anchors > 15 || knee_x == 0 || knee_y == 0) return false;
            scene.hdr10plus.valid = true;
            scene.hdr10plus.knee_x = knee_x / 4095.0F;
            scene.hdr10plus.knee_y = knee_y / 4095.0F;
            scene.hdr10plus.anchor_count = anchors;
            float previous = 0.0F;
            for (std::size_t index = 0; index < anchors; ++index) {
                const auto anchor = reader.get(10);
                if (!reader.valid() || anchor < previous * 1023.0F) return false;
                scene.hdr10plus.anchors[index] = anchor / 1023.0F;
                previous = scene.hdr10plus.anchors[index];
            }
        }
        const auto saturation = reader.get(1);
        if (saturation != 0) {
            const auto weight = reader.get(6);
            if (!reader.valid()) return false;
            scene.hdr10plus.saturation_weight = weight / 8.0F;
        }
    }
    if (!reader.finish()) return false;
    scene.average_nits = average * 0.1F;
    scene.maximum_nits = std::max(peak, scene.average_nits);
    scene.variance_pq = std::max(0.0F, nits_to_pq(high) - nits_to_pq(low));
    return true;
}

bool parse_vivid(const std::uint8_t* data, std::size_t size, dynamic_hdr_scene& scene) {
    const auto version = static_cast<std::uint8_t>(scene.format == DYNAMIC_HDR_FORMAT_VIVID_HLG ? 2 : 1);
    if (size < 13 || size > 512 || !prefix(data, size, {0x26, 0, 4, 0, 5, version})) return false;
    bits reader(data + 6, size - 6);
    const auto minimum = reader.get(12);
    const auto average = reader.get(12);
    const auto variance = reader.get(12);
    const auto maximum = reader.get(12);
    if (minimum > average || average > maximum || !reader.valid()) return false;
    scene.minimum_nits = pq_to_nits(minimum / 4095.0F);
    scene.average_nits = pq_to_nits(average / 4095.0F);
    scene.median_nits = scene.average_nits;
    scene.maximum_nits = pq_to_nits(maximum / 4095.0F);
    scene.variance_pq = variance / 4095.0F;
    const auto tone_mapping = reader.get(1);
    if (!reader.valid()) return false;
    if (tone_mapping != 0) {
        const auto parameter_sets_minus_one = reader.get(1);
        if (!reader.valid() || parameter_sets_minus_one != 0) return false;
        const auto target = reader.get(12);
        const auto base_enabled = reader.get(1);
        if (!reader.valid()) return false;
        if (base_enabled != 0 && target == 0) return false;
        std::uint16_t base_m_p = 0;
        std::uint8_t base_m_m = 0;
        std::uint16_t base_m_a = 0;
        std::uint16_t base_m_b = 0;
        std::uint8_t base_m_n = 0;
        std::uint8_t base_k1 = 0;
        std::uint8_t base_k2 = 0;
        std::uint8_t base_k3 = 0;
        std::uint8_t base_delta_mode = 0;
        std::uint8_t base_delta = 0;
        if (base_enabled != 0) {
            if (!reader.expect(1, 1)) return false; // marker_bit
            base_m_p = reader.get(14);
            base_m_m = static_cast<std::uint8_t>(reader.get(6));
            if (!reader.expect(1, 1)) return false; // marker_bit
            base_m_a = static_cast<std::uint16_t>(reader.get(10));
            base_m_b = static_cast<std::uint16_t>(reader.get(10));
            if (!reader.expect(1, 1)) return false; // marker_bit
            base_m_n = static_cast<std::uint8_t>(reader.get(6));
            base_k1 = static_cast<std::uint8_t>(reader.get(2));
            base_k2 = static_cast<std::uint8_t>(reader.get(2));
            base_k3 = static_cast<std::uint8_t>(reader.get(4));
            base_delta_mode = static_cast<std::uint8_t>(reader.get(3));
            base_delta = static_cast<std::uint8_t>(reader.get(7));
            if (!reader.valid()) return false;
        }
        const auto spline = reader.get(1);
        if (!reader.valid()) return false;
        if (spline != 0) {
            // The desktop producer currently emits the normative base curve;
            // reject an unimplemented spline instead of silently discarding it.
            return false;
        }
        scene.vivid_base.valid = base_enabled != 0;
        if (scene.vivid_base.valid) {
            scene.vivid_base.target_pq = target / 4095.0F;
            scene.vivid_base.m_p = base_m_p * 10.0F / 16383.0F;
            scene.vivid_base.m_m = base_m_m / 10.0F;
            scene.vivid_base.m_a = base_m_a / 1023.0F;
            scene.vivid_base.m_b = base_m_b * 0.25F / 1023.0F;
            scene.vivid_base.m_n = base_m_n / 10.0F;
            scene.vivid_base.k1 = base_k1;
            scene.vivid_base.k2 = base_k2;
            scene.vivid_base.k3 = base_k3;
            scene.vivid_base.delta_mode = base_delta_mode;
            scene.vivid_base.delta = base_delta / 127.0F;
            if (scene.vivid_base.delta_mode == 2 || scene.vivid_base.delta_mode == 6) {
                scene.vivid_base.delta = -scene.vivid_base.delta;
            }
            if (!std::isfinite(scene.vivid_base.m_p) || scene.vivid_base.m_p < 0.0F ||
                scene.vivid_base.m_p > 10.0F || scene.vivid_base.m_m < 0.0F ||
                scene.vivid_base.m_m > 6.3F || scene.vivid_base.m_a < 0.0F ||
                scene.vivid_base.m_a > 1.0F || scene.vivid_base.m_b < 0.0F ||
                scene.vivid_base.m_b > 0.25F || scene.vivid_base.m_n < 0.0F ||
                scene.vivid_base.m_n > 6.3F || scene.vivid_base.delta_mode > 6) return false;
        }
        const auto saturation = reader.get(1);
        if (!reader.valid()) return false;
        if (saturation != 0) {
            const auto count = reader.get(3);
            if (!reader.valid() || count > 7) return false;
            if (count != 0) {
                scene.hdr10plus.saturation_weight = reader.get(8) / 128.0F;
                for (std::size_t i = 1; i < count; ++i) (void) reader.get(8);
            }
        }
    } else {
        if (!reader.expect(1, 0)) return false; // saturation mapping flag
    }
    if (!reader.finish()) return false;
    return true;
}

std::uint32_t crc_mpeg2(const std::uint8_t* data, std::size_t size) {
    std::uint32_t crc = 0xFFFFFFFFU;
    for (std::size_t i = 0; i < size; ++i) {
        crc ^= std::uint32_t(data[i]) << 24;
        for (unsigned bit = 0; bit < 8; ++bit) {
            crc = (crc << 1) ^ ((crc & 0x80000000U) != 0 ? 0x04C11DB7U : 0U);
        }
    }
    return crc;
}

bool parse_rpu(const std::uint8_t* data, std::size_t size, int width, int height, dynamic_hdr_scene& scene) {
    constexpr std::size_t max_rpu_payload_size = 4096;
    if (size < 8 || size > max_rpu_payload_size || data[0] != 0x7C || data[1] != 1) return false;
    std::array<std::uint8_t, max_rpu_payload_size> rbsp{};
    std::size_t count = 0;
    unsigned zeros = 0;
    for (std::size_t i = 2; i < size; ++i) {
        const auto value = data[i];
        if (zeros == 2 && value == 3) {
            if (i + 1 == size || data[i + 1] > 3) return false;
            zeros = 0;
            continue;
        }
        if (zeros == 2 && value < 3) return false;
        rbsp[count++] = value;
        zeros = value == 0 ? zeros + 1 : 0;
    }
    if (count < 6 || rbsp[count - 1] != 0x80 ||
        crc_mpeg2(rbsp.data() + 1, count - 6) != be32(rbsp.data() + count - 5)) return false;
    bits reader(rbsp.data(), count - 5);
    if (!reader.expect(8, 0x19) || !reader.expect(6, 2) || !reader.expect(11, 18) ||
        !reader.expect(4, 1) || !reader.expect(4, 0) || !reader.expect(1, 1) ||
        !reader.expect(1, 0) || !reader.expect(2, 0) || !reader.expect_ue(23) ||
        !reader.expect(2, 1) || !reader.expect(1, 0) ||
        !reader.expect_ue(2) || !reader.expect_ue(2) || !reader.expect_ue(4) ||
        !reader.expect(1, 0) || !reader.expect(3, 0) || !reader.expect(1, 0) ||
        !reader.expect(1, 1) || !reader.expect(1, 1) || !reader.expect(1, 0) ||
        !reader.expect_ue(0) || !reader.expect_ue(0) || !reader.expect_ue(0)) return false;
    for (unsigned component = 0; component < 3; ++component) {
        const auto pivot_minus_two = reader.ue();
        if (!reader.valid() || pivot_minus_two > 7) return false;
        auto &curve = scene.dolby_reshape[component];
        curve.pivot_count = pivot_minus_two + 2;
        for (std::size_t pivot = 0; pivot < curve.pivot_count; ++pivot) {
            const auto value = reader.get(10);
            if (!reader.valid() || (pivot != 0 && value / 1023.0F < curve.pivots[pivot - 1])) return false;
            curve.pivots[pivot] = value / 1023.0F;
        }
        curve.valid = true;
    }
    if (!reader.expect_ue(0) || !reader.expect_ue(0)) return false;
    for (unsigned component = 0; component < 3; ++component) {
        auto &curve = scene.dolby_reshape[component];
        const auto piece_count = curve.pivot_count - 1;
        for (std::uint32_t piece = 0; piece < piece_count; ++piece) {
            const auto mapping_idc = reader.ue();
            if (!reader.valid() || mapping_idc > 1) return false;
            curve.mapping_idc[piece] = static_cast<std::uint8_t>(mapping_idc);
            if (mapping_idc == 0) {
                const auto order_minus_one = reader.ue();
                if (!reader.valid() || order_minus_one > 1) return false;
                const auto order = order_minus_one + 1;
                const auto linear = order_minus_one == 0 ? reader.get(1) : 0;
                if (!reader.valid() || linear != 0) return false;
                curve.poly_order[piece] = static_cast<std::uint8_t>(order);
                for (std::size_t coefficient = 0; coefficient <= order; ++coefficient) {
                    const auto integer = reader.se();
                    const auto fractional = reader.get(23);
                    if (!reader.valid()) return false;
                    curve.poly_coefficients[piece][coefficient] =
                        static_cast<float>(integer) + fractional / 8388608.0F;
                }
            } else {
                const auto order_minus_one = reader.get(2);
                if (!reader.valid() || order_minus_one > 2) return false;
                const auto order = order_minus_one + 1;
                curve.mmr_order[piece] = static_cast<std::uint8_t>(order);
                const auto integer = reader.se();
                const auto fractional = reader.get(23);
                if (!reader.valid()) return false;
                curve.mmr_constant[piece] = static_cast<float>(integer) + fractional / 8388608.0F;
                for (std::size_t row = 0; row < order; ++row) {
                    for (std::size_t coefficient = 0; coefficient < 7; ++coefficient) {
                        const auto coeff_integer = reader.se();
                        const auto coeff_fractional = reader.get(23);
                        if (!reader.valid()) return false;
                        curve.mmr_coefficients[piece][row][coefficient] =
                            static_cast<float>(coeff_integer) + coeff_fractional / 8388608.0F;
                    }
                }
            }
        }
        curve.valid = true;
    }
    if (!reader.expect_ue(0) || !reader.expect_ue(0)) return false;
    const auto refresh = reader.ue();
    if (refresh > 1) return false;
    scene.scene_refresh = refresh != 0;
    for (auto &value : scene.dolby_ycc_to_rgb) {
        value = static_cast<std::int16_t>(reader.get(16));
        if (!reader.valid()) return false;
    }
    for (auto &value : scene.dolby_ycc_offsets) {
        value = reader.get(32);
        if (!reader.valid()) return false;
    }
    scene.dolby_matrix_valid = true;
    for (auto &value : scene.dolby_rgb_to_lms) {
        value = static_cast<std::int16_t>(reader.get(16));
        if (!reader.valid()) return false;
    }
    scene.dolby_lms_matrix_valid = true;
    const auto signal_eotf = reader.get(16);
    const auto signal_eotf_param0 = reader.get(16);
    const auto signal_eotf_param1 = reader.get(16);
    const auto signal_eotf_param2 = reader.get(32);
    const auto signal_bit_depth = reader.get(5);
    const auto signal_color_space = reader.get(2);
    const auto signal_chroma = reader.get(2);
    const auto signal_range = reader.get(2);
    (void) signal_eotf_param0;
    (void) signal_eotf_param1;
    (void) signal_eotf_param2;
    if (!reader.valid() || signal_eotf == 0 || signal_bit_depth < 8 ||
        signal_color_space > 3 || signal_chroma > 3 || signal_range > 3) return false;
    const auto source_min = reader.get(12);
    const auto source_max = reader.get(12);
    (void) reader.get(10); // source_diagonal
    if (source_min > source_max || !reader.valid()) return false;
    const auto ext_blocks = reader.ue();
    if (!reader.valid() || ext_blocks == 0 || ext_blocks > 32 || !reader.align()) return false;
    std::uint16_t minimum = 0;
    std::uint16_t maximum = 0;
    std::uint16_t average = 0;
    std::uint16_t mastering_peak = 0;
    std::uint16_t mastering_min = 0;
    std::uint16_t max_cll = 0;
    std::uint16_t max_fall = 0;
    for (std::uint32_t block = 0; block < ext_blocks; ++block) {
        const auto block_length = reader.ue();
        const auto block_level = reader.get(8);
        if (!reader.valid() || block_length > 1023) return false;
        const auto payload_bits = block_length * 8U;
        std::uint32_t consumed = 0;
        if (block_level == 1) {
            if (payload_bits < 36) return false;
            minimum = reader.get(12);
            maximum = reader.get(12);
            average = reader.get(12);
            consumed = 36;
            if (minimum > maximum || average < minimum || average > maximum) return false;
        } else if (block_level == 2) {
            if (payload_bits < 85) return false;
            scene.dolby_trim_target_pq = static_cast<std::uint16_t>(reader.get(12));
            for (auto &value : scene.dolby_trim_values) value = static_cast<std::uint16_t>(reader.get(12));
            (void) reader.get(13); // reserved ms_weight
            consumed = 85;
            scene.dolby_trim_valid = true;
        } else if (block_level == 5) {
            if (payload_bits < 52) return false;
            for (auto &offset_value : scene.active_area) offset_value = static_cast<std::uint16_t>(reader.get(13));
            consumed = 52;
            if (std::uint32_t(scene.active_area[0]) + scene.active_area[1] >= std::uint32_t(width) ||
                std::uint32_t(scene.active_area[2]) + scene.active_area[3] >= std::uint32_t(height)) return false;
        } else if (block_level == 6) {
            if (payload_bits < 64) return false;
            mastering_peak = static_cast<std::uint16_t>(reader.get(16));
            mastering_min = static_cast<std::uint16_t>(reader.get(16));
            max_cll = static_cast<std::uint16_t>(reader.get(16));
            max_fall = static_cast<std::uint16_t>(reader.get(16));
            consumed = 64;
        }
        if (!reader.valid() || consumed > payload_bits) return false;
        if (consumed == 0) {
            // Unknown Dolby DM levels have their own syntax; the bytes are
            // bounded by ext_block_length and do not change our L1/L2/L5/L6
            // interpretation.
            for (std::uint32_t bit = 0; bit < payload_bits; ++bit) {
                (void) reader.get(1);
            }
        } else {
            for (std::uint32_t bit = consumed; bit < payload_bits; ++bit) {
                if (!reader.expect(1, 0)) return false;
            }
        }
        // Dolby DM blocks are bit-concatenated. ext_block_length counts only
        // the payload after block_level; it does not realign the bitstream
        // between blocks.
    }
    if (minimum == 0 && maximum == 0) return false;
    if (mastering_peak == 0) mastering_peak = 1000;
    if (mastering_min == 0) mastering_min = 1;
    if (mastering_peak > 10000 || mastering_min > 10000 || max_cll > 10000 || max_fall > 10000 ||
        !reader.align() || !reader.finish()) return false;
    scene.minimum_nits = pq_to_nits(minimum / 4095.0F);
    scene.maximum_nits = pq_to_nits(maximum / 4095.0F);
    scene.average_nits = pq_to_nits(average / 4095.0F);
    scene.median_nits = scene.average_nits;
    scene.mastering_peak_nits = static_cast<float>(mastering_peak);
    scene.mastering_minimum_nits = mastering_min / 10000.0F;
    scene.max_cll_nits = static_cast<float>(max_cll);
    scene.max_fall_nits = static_cast<float>(max_fall);
    return true;
}

std::uint16_t expected_type(int format) {
    switch (format) {
    case DYNAMIC_HDR_FORMAT_HDR10_PLUS: return LI_PYROWAVE_METADATA_HDR10_PLUS;
    case DYNAMIC_HDR_FORMAT_VIVID_PQ:
    case DYNAMIC_HDR_FORMAT_VIVID_HLG: return LI_PYROWAVE_METADATA_HDR_VIVID;
    case DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_81:
    case DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_84: return LI_PYROWAVE_METADATA_DOLBY_VISION_RPU;
    default: return 0;
    }
}

} // namespace

float pq_to_nits(float signal) noexcept {
    if (!std::isfinite(signal) || signal <= 0) return 0;
    constexpr double m1 = 2610.0 / 16384.0;
    constexpr double m2 = 2523.0 / 32.0;
    constexpr double c1 = 3424.0 / 4096.0;
    constexpr double c2 = 2413.0 / 128.0;
    constexpr double c3 = 2392.0 / 128.0;
    const auto p = std::pow(std::clamp<double>(signal, 0, 1), 1.0 / m2);
    return static_cast<float>(10000.0 * std::pow(std::max(p - c1, 0.0) / (c2 - c3 * p), 1.0 / m1));
}

float nits_to_pq(float nits) noexcept {
    if (!std::isfinite(nits) || nits <= 0) return 0;
    const auto p = std::pow(std::clamp<double>(nits / 10000.0, 0, 1), 2610.0 / 16384.0);
    return static_cast<float>(std::pow((3424.0 / 4096.0 + 2413.0 / 128.0 * p) /
                                      (1.0 + 2392.0 / 128.0 * p), 2523.0 / 32.0));
}

bool parse_dynamic_hdr_frame(const std::uint8_t* data, std::size_t length,
                             int format, int width, int height, dynamic_hdr_scene& scene,
                             dynamic_hdr_payload* vivid) noexcept {
    scene = {};
    if (vivid != nullptr) *vivid = {};
    if (data == nullptr || length == 0 || length > LI_PYROWAVE_MAX_METADATA_SIZE ||
        width <= 0 || height <= 0 || expected_type(format) == 0) return false;
    dynamic_hdr_scene candidate;
    candidate.format = format;
    dynamic_hdr_payload vivid_candidate{};
    bool found = false;
    bool hlg_peak_found = false;
    std::size_t offset = 0;
    while (offset < length) {
        if (length - offset < 8) return false;
        const auto type = be16(data + offset);
        const auto flags = be16(data + offset + 2);
        const auto payload_size = be32(data + offset + 4);
        if ((flags & ~(LI_PYROWAVE_METADATA_FLAG_PROTECTED | LI_PYROWAVE_METADATA_FLAG_RUNTIME |
                       LI_PYROWAVE_METADATA_FLAG_OPTIONAL | LI_PYROWAVE_METADATA_FLAG_REQUIRED)) != 0 ||
            ((flags & LI_PYROWAVE_METADATA_FLAG_OPTIONAL) != 0 &&
             (flags & LI_PYROWAVE_METADATA_FLAG_REQUIRED) != 0) ||
            payload_size > length - offset - 8) return false;
        const auto* payload = data + offset + 8;
        if (type == LI_PYROWAVE_METADATA_HDR10_PLUS || type == LI_PYROWAVE_METADATA_HDR_VIVID ||
            type == LI_PYROWAVE_METADATA_DOLBY_VISION_RPU) {
            if (type != expected_type(format) || found ||
                (flags & LI_PYROWAVE_METADATA_FLAG_PROTECTED) == 0) return false;
            const bool valid = type == LI_PYROWAVE_METADATA_HDR10_PLUS
                ? parse_hdr10plus(payload, payload_size, candidate)
                : type == LI_PYROWAVE_METADATA_HDR_VIVID
                    ? parse_vivid(payload, payload_size, candidate)
                    : parse_rpu(payload, payload_size, width, height, candidate);
            if (!valid) return false;
            if (type == LI_PYROWAVE_METADATA_HDR_VIVID) {
                // UWA 005.1 section 7.3 defines dynamic_metadata(); section
                // 8.2 wraps it in country/provider/oriented codes for T.35.
                vivid_candidate = {payload + 5, payload_size - 5};
            }
            found = true;
        } else if (type == LI_PYROWAVE_METADATA_HLG_NOMINAL_PEAK) {
            if (hlg_peak_found || payload_size != 2 ||
                (format != DYNAMIC_HDR_FORMAT_VIVID_HLG &&
                 format != DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_84) ||
                (flags & LI_PYROWAVE_METADATA_FLAG_PROTECTED) == 0 || be16(payload) == 0) return false;
            candidate.hlg_nominal_peak_nits = be16(payload);
            hlg_peak_found = true;
        } else if ((flags & LI_PYROWAVE_METADATA_FLAG_REQUIRED) != 0) {
            return false;
        }
        offset += 8 + payload_size;
    }
    if (!found || ((format == DYNAMIC_HDR_FORMAT_VIVID_HLG ||
                    format == DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_84) && !hlg_peak_found)) return false;
    scene = candidate;
    if (vivid != nullptr) *vivid = vivid_candidate;
    return true;
}

bool build_dynamic_hdr_lut(const dynamic_hdr_scene& scene, float target_peak_nits,
                           dynamic_hdr_lut& lut) noexcept {
    const std::array values{scene.minimum_nits, scene.maximum_nits, scene.average_nits,
                            scene.median_nits, scene.variance_pq, target_peak_nits};
    if (expected_type(scene.format) == 0 ||
        std::any_of(values.begin(), values.end(), [](float x) { return !std::isfinite(x) || x < 0; }) ||
        target_peak_nits < 1 || target_peak_nits > 10000 || scene.maximum_nits > 10000 ||
        scene.minimum_nits > scene.maximum_nits || scene.variance_pq > 1) return false;
    const auto source_peak = std::max(scene.maximum_nits, 1.0F);
    const auto target_peak = std::min(source_peak, target_peak_nits);
    const auto source_pq = nits_to_pq(source_peak);
    const auto target_pq = nits_to_pq(target_peak);
    auto output_limit_pq = nits_to_pq(target_peak_nits);
    if (scene.dolby_trim_valid && scene.dolby_trim_target_pq != 0) {
        output_limit_pq = std::min(output_limit_pq, scene.dolby_trim_target_pq / 4095.0F);
    }
    const auto content_anchor = nits_to_pq(std::max(scene.average_nits, scene.median_nits));
    const auto knee_floor = target_pq * (0.35F + 0.15F * scene.variance_pq);
    const auto knee = std::clamp(content_anchor, knee_floor, target_pq * 0.8F);
    const auto span = std::max(source_pq - knee, 0.000001F);
    const auto rise = std::max(target_pq - knee, 0.0F);
    const auto slope = std::min(1.0F, 3.0F * rise / span);
    float previous = 0;
    const auto evaluate_bezier = [&](float t) {
        const auto count = scene.hdr10plus.anchor_count + 1;
        std::array<float, 17> points{};
        points[0] = 0.0F;
        for (std::size_t i = 0; i < scene.hdr10plus.anchor_count; ++i) {
            points[i + 1] = scene.hdr10plus.anchors[i];
        }
        points[count] = 1.0F;
        float value = 0.0F;
        // Bernstein form is stable for the small (<=16) curve mandated by
        // ST 2094-40 and matches the receiver-side Annex B construction.
        for (std::size_t i = 0; i <= count; ++i) {
            double coefficient = 1.0;
            for (std::size_t j = 1; j <= i; ++j) {
                coefficient *= static_cast<double>(count - j + 1) / static_cast<double>(j);
            }
            value += static_cast<float>(coefficient * std::pow(t, static_cast<int>(i)) *
                std::pow(1.0F - t, static_cast<int>(count - i)) * points[i]);
        }
        return value;
    };
    for (std::size_t i = 0; i < lut.size(); ++i) {
        const auto x = static_cast<float>(i) / static_cast<float>(lut.size() - 1);
        float mapped = std::min(x, output_limit_pq);
        if (scene.vivid_base.valid) {
            const auto exponent = std::max(scene.vivid_base.m_n, 0.0001F);
            const auto base = std::pow(std::clamp(x, 0.0F, 1.0F), exponent);
            const auto denominator = (scene.vivid_base.m_p - 1.0F) * base + 1.0F;
            if (std::abs(denominator) < 0.000001F) return false;
            mapped = scene.vivid_base.m_a *
                std::pow(std::clamp(scene.vivid_base.m_p * base / denominator, 0.0F, 1.0F),
                         std::max(scene.vivid_base.m_m, 0.0001F)) + scene.vivid_base.m_b;
            if (scene.vivid_base.delta_mode != 0) mapped += scene.vivid_base.delta;
        } else if (scene.hdr10plus.valid) {
            if (x <= scene.hdr10plus.knee_x && scene.hdr10plus.knee_x > 0.0F) {
                mapped = x * scene.hdr10plus.knee_y / scene.hdr10plus.knee_x;
            } else if (x > scene.hdr10plus.knee_x && scene.hdr10plus.knee_x < 1.0F) {
                const auto t = std::clamp((x - scene.hdr10plus.knee_x) /
                    (1.0F - scene.hdr10plus.knee_x), 0.0F, 1.0F);
                mapped = scene.hdr10plus.knee_y +
                    (1.0F - scene.hdr10plus.knee_y) * evaluate_bezier(t);
            }
        } else if (source_peak > target_peak_nits && x > knee) {
            const auto t = std::clamp((x - knee) / span, 0.0F, 1.0F);
            const auto t2 = t * t;
            const auto t3 = t2 * t;
            mapped = (2 * t3 - 3 * t2 + 1) * knee +
                     (t3 - 2 * t2 + t) * span * slope + (-2 * t3 + 3 * t2) * target_pq;
        }
        if ((scene.format == DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_81 ||
             scene.format == DYNAMIC_HDR_FORMAT_DOLBY_VISION_PROFILE_84) && scene.dolby_trim_valid) {
            const auto slope = scene.dolby_trim_values[0] / 2048.0F;
            const auto offset = (scene.dolby_trim_values[1] - 2048.0F) / 4095.0F;
            const auto power = std::clamp(scene.dolby_trim_values[2] / 2048.0F, 0.25F, 4.0F);
            mapped = std::pow(std::clamp(mapped * slope + offset, 0.0F, 1.0F), 1.0F / power);
        }
        mapped = std::clamp(mapped, previous, std::min(x, output_limit_pq));
        lut[i] = mapped;
        previous = mapped;
    }
    return true;
}

bool build_dynamic_hdr_mapping(const dynamic_hdr_scene& scene, float target_peak_nits,
                               dynamic_hdr_mapping& mapping) noexcept {
    if (!build_dynamic_hdr_lut(scene, target_peak_nits, mapping.pq_mapping)) return false;
    mapping.mapping_flags = {0.0F, 0.0F, 0.0F, 0.0F};
    const auto invert = [](const std::array<std::int16_t, 9>& input,
                           std::array<float, 9>& output) {
        const auto a = static_cast<float>(input[0]) / 16384.0F;
        const auto b = static_cast<float>(input[1]) / 16384.0F;
        const auto c = static_cast<float>(input[2]) / 16384.0F;
        const auto d = static_cast<float>(input[3]) / 16384.0F;
        const auto e = static_cast<float>(input[4]) / 16384.0F;
        const auto f = static_cast<float>(input[5]) / 16384.0F;
        const auto g = static_cast<float>(input[6]) / 16384.0F;
        const auto h = static_cast<float>(input[7]) / 16384.0F;
        const auto i = static_cast<float>(input[8]) / 16384.0F;
        const auto determinant = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (!std::isfinite(determinant) || std::abs(determinant) < 0.000001F) return false;
        const auto scale = 1.0F / determinant;
        output = {
            (e * i - f * h) * scale, (c * h - b * i) * scale, (b * f - c * e) * scale,
            (f * g - d * i) * scale, (a * i - c * g) * scale, (c * d - a * f) * scale,
            (d * h - e * g) * scale, (b * g - a * h) * scale, (a * e - b * d) * scale,
        };
        return std::all_of(output.begin(), output.end(), [](float value) { return std::isfinite(value); });
    };
    for (std::size_t component = 0; component < scene.dolby_reshape.size(); ++component) {
        const auto &source = scene.dolby_reshape[component];
        auto &destination = mapping.dolby[component];
        if (!source.valid || source.pivot_count < 2 || source.pivot_count > 9) continue;
        mapping.mapping_flags[1] = 1.0F;
        for (std::size_t pivot = 0; pivot < source.pivot_count; ++pivot) {
            destination.pivots[pivot] = source.pivots[pivot];
        }
        for (std::size_t piece = 0; piece + 1 < source.pivot_count; ++piece) {
            const auto method = source.mapping_idc[piece];
            destination.piece_info[piece] = {
                static_cast<float>(method), static_cast<float>(source.poly_order[piece]),
                static_cast<float>(source.mmr_order[piece]), source.mmr_constant[piece],
            };
            for (std::size_t coefficient = 0; coefficient < 3; ++coefficient) {
                destination.polynomial[piece][0][coefficient] = source.poly_coefficients[piece][coefficient];
            }
            for (std::size_t row = 0; row < 3; ++row) {
                for (std::size_t coefficient = 0; coefficient < 4; ++coefficient) {
                    destination.mmr[piece][row][0][coefficient] =
                        source.mmr_coefficients[piece][row][coefficient];
                }
                for (std::size_t coefficient = 0; coefficient < 3; ++coefficient) {
                    destination.mmr[piece][row][1][coefficient] =
                        source.mmr_coefficients[piece][row][coefficient + 4];
                }
            }
        }
    }
    mapping.ycc_to_rgb = {{
        {1.0F, 0.0F, 1.4746F, 0.0F},
        {1.0F, -0.16455F, -0.57135F, 0.0F},
        {1.0F, 1.8814F, 0.0F, 0.0F},
    }};
    mapping.ycc_offsets = {0.0F, 0.0F, 0.0F, 0.0F};
    mapping.lms_to_rgb = {{
        {1.0F, 0.0F, 0.0F, 0.0F},
        {0.0F, 1.0F, 0.0F, 0.0F},
        {0.0F, 0.0F, 1.0F, 0.0F},
    }};
    if (scene.dolby_matrix_valid) {
        mapping.mapping_flags[2] = 1.0F;
        for (std::size_t row = 0; row < 3; ++row) {
            for (std::size_t column = 0; column < 3; ++column) {
                mapping.ycc_to_rgb[row][column] =
                    scene.dolby_ycc_to_rgb[row * 3 + column] / 8192.0F;
            }
            mapping.ycc_offsets[row] = scene.dolby_ycc_offsets[row] / 134217728.0F;
        }
        if (scene.dolby_lms_matrix_valid) {
            std::array<float, 9> inverse{};
            if (!invert(scene.dolby_rgb_to_lms, inverse)) return false;
            for (std::size_t row = 0; row < 3; ++row) {
                for (std::size_t column = 0; column < 3; ++column) {
                    mapping.lms_to_rgb[row][column] = inverse[row * 3 + column];
                }
            }
            mapping.mapping_flags[0] = 1.0F;
        }
    }
    return true;
}

} // namespace moonlight::pyrowave
