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
        if (count > 32 || count > size_ - position_) {
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
        return valid_ && size_ - position_ < 8 && expect(static_cast<unsigned>(size_ - position_), 0);
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
    // Sunshine currently emits the single-window statistics profile: no
    // spatial peak grids, authored tone curve or saturation remapping.
    if (!reader.expect(10, 0) || !reader.expect(1, 0) ||
        !reader.expect(1, 0) || !reader.expect(1, 0) || !reader.finish()) return false;
    scene.average_nits = average * 0.1F;
    scene.maximum_nits = std::max(peak, scene.average_nits);
    scene.variance_pq = std::max(0.0F, nits_to_pq(high) - nits_to_pq(low));
    return true;
}

bool parse_vivid(const std::uint8_t* data, std::size_t size, dynamic_hdr_scene& scene) {
    if (size != 13 || !prefix(data, size, {0x26, 0, 4, 0, 5, 1})) return false;
    bits reader(data + 6, size - 6);
    const auto minimum = reader.get(12);
    const auto average = reader.get(12);
    const auto variance = reader.get(12);
    const auto maximum = reader.get(12);
    if (minimum > average || average > maximum ||
        !reader.expect(1, 0) || !reader.expect(1, 0) || !reader.finish()) return false;
    scene.minimum_nits = pq_to_nits(minimum / 4095.0F);
    scene.average_nits = pq_to_nits(average / 4095.0F);
    scene.median_nits = scene.average_nits;
    scene.maximum_nits = pq_to_nits(maximum / 4095.0F);
    scene.variance_pq = variance / 4095.0F;
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
    if (size < 8 || size > 256 || data[0] != 0x7C || data[1] != 1) return false;
    std::array<std::uint8_t, 256> rbsp{};
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
        if (!reader.expect_ue(0) || !reader.expect(10, 0) || !reader.expect(10, 1023)) return false;
    }
    if (!reader.expect_ue(0) || !reader.expect_ue(0)) return false;
    for (unsigned component = 0; component < 3; ++component) {
        if (!reader.expect_ue(0) || !reader.expect_ue(0) || !reader.expect(1, 0) ||
            reader.se() != 0 || !reader.expect(23, 0) ||
            reader.se() != 1 || !reader.expect(23, 0)) return false;
    }
    if (!reader.expect_ue(0) || !reader.expect_ue(0)) return false;
    const auto refresh = reader.ue();
    if (refresh > 1) return false;
    scene.scene_refresh = refresh != 0;
    constexpr std::array<std::int16_t, 9> ycc{9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0};
    for (auto value : ycc) {
        if (!reader.expect(16, static_cast<std::uint16_t>(value))) return false;
    }
    if (!reader.expect(32, 16777216) || !reader.expect(32, 134217728) ||
        !reader.expect(32, 134217728)) return false;
    constexpr std::array<std::int16_t, 9> lms{7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962};
    for (auto value : lms) {
        if (!reader.expect(16, static_cast<std::uint16_t>(value))) return false;
    }
    if (!reader.expect(16, 65535) || !reader.expect(16, 0) || !reader.expect(16, 0) ||
        !reader.expect(32, 0) || !reader.expect(5, 12) || !reader.expect(2, 0) ||
        !reader.expect(2, 0) || !reader.expect(2, 1)) return false;
    const auto source_min = reader.get(12);
    const auto source_max = reader.get(12);
    if (source_min > source_max || !reader.expect(10, 42) ||
        !reader.expect_ue(3) || !reader.align() ||
        !reader.expect_ue(5) || !reader.expect(8, 1)) return false;
    const auto minimum = reader.get(12);
    const auto maximum = reader.get(12);
    const auto average = reader.get(12);
    if (minimum > 12 || maximum < 2081 || average < 819 || average >= maximum ||
        !reader.expect(4, 0) || !reader.expect_ue(7) || !reader.expect(8, 5)) return false;
    for (auto& offset : scene.active_area) offset = static_cast<std::uint16_t>(reader.get(13));
    if (std::uint32_t(scene.active_area[0]) + scene.active_area[1] >= std::uint32_t(width) ||
        std::uint32_t(scene.active_area[2]) + scene.active_area[3] >= std::uint32_t(height) ||
        !reader.expect(4, 0) || !reader.expect_ue(8) || !reader.expect(8, 6)) return false;
    const auto mastering_peak = reader.get(16);
    const auto mastering_min = reader.get(16);
    const auto max_cll = reader.get(16);
    const auto max_fall = reader.get(16);
    if (mastering_peak == 0 || mastering_peak > 10000 || mastering_min == 0 ||
        mastering_min > 10000 || max_cll > 10000 || max_fall > 10000 ||
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
                             int format, int width, int height, dynamic_hdr_scene& scene) noexcept {
    scene = {};
    if (data == nullptr || length == 0 || length > LI_PYROWAVE_MAX_METADATA_SIZE ||
        width <= 0 || height <= 0 || expected_type(format) == 0) return false;
    dynamic_hdr_scene candidate;
    candidate.format = format;
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
    const auto output_limit_pq = nits_to_pq(target_peak_nits);
    const auto content_anchor = nits_to_pq(std::max(scene.average_nits, scene.median_nits));
    const auto knee_floor = target_pq * (0.35F + 0.15F * scene.variance_pq);
    const auto knee = std::clamp(content_anchor, knee_floor, target_pq * 0.8F);
    const auto span = std::max(source_pq - knee, 0.000001F);
    const auto rise = std::max(target_pq - knee, 0.0F);
    const auto slope = std::min(1.0F, 3.0F * rise / span);
    float previous = 0;
    for (std::size_t i = 0; i < lut.size(); ++i) {
        const auto x = static_cast<float>(i) / static_cast<float>(lut.size() - 1);
        float mapped = std::min(x, output_limit_pq);
        if (source_peak > target_peak_nits && x > knee) {
            const auto t = std::clamp((x - knee) / span, 0.0F, 1.0F);
            const auto t2 = t * t;
            const auto t3 = t2 * t;
            mapped = (2 * t3 - 3 * t2 + 1) * knee +
                     (t3 - 2 * t2 + t) * span * slope + (-2 * t3 + 3 * t2) * target_pq;
        }
        mapped = std::clamp(mapped, previous, x);
        lut[i] = mapped;
        previous = mapped;
    }
    return true;
}

} // namespace moonlight::pyrowave
