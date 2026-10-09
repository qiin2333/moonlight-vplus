#include "pyrowave_dynamic_hdr.hpp"
#include "moonlight-common-c/src/PyrowaveProtocol.h"
#include "moonlight-common-c/tests/PyrowaveDynamicHdrFixtures.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <limits>
#include <vector>

#define CHECK(condition) do { if (!(condition)) { \
    std::fprintf(stderr, "FAIL line %d: %s\n", __LINE__, #condition); return 1; \
} } while (0)

static std::vector<std::uint8_t> from_hex(const char* hex) {
    std::vector<std::uint8_t> bytes;
    const auto digit = [](char c) { return c <= '9' ? c - '0' : c - 'a' + 10; };
    for (std::size_t i = 0; i < std::strlen(hex); i += 2) {
        bytes.push_back(static_cast<std::uint8_t>((digit(hex[i]) << 4) | digit(hex[i + 1])));
    }
    return bytes;
}

static void tlv(std::vector<std::uint8_t>& out, std::uint16_t type, std::uint16_t flags,
                const std::vector<std::uint8_t>& bytes) {
    out.insert(out.end(), {static_cast<std::uint8_t>(type >> 8), static_cast<std::uint8_t>(type),
                          static_cast<std::uint8_t>(flags >> 8), static_cast<std::uint8_t>(flags),
                          0, 0, static_cast<std::uint8_t>(bytes.size() >> 8), static_cast<std::uint8_t>(bytes.size())});
    out.insert(out.end(), bytes.begin(), bytes.end());
}

int main() {
    using namespace moonlight::pyrowave;
    constexpr auto protected_required = LI_PYROWAVE_METADATA_FLAG_PROTECTED | LI_PYROWAVE_METADATA_FLAG_REQUIRED;
    dynamic_hdr_scene scene;
    dynamic_hdr_lut lut{};
    for (const auto& fixture : PyrowaveDynamicHdrFixtures) {
        const auto payload = from_hex(fixture.payloadHex);
        std::vector<std::uint8_t> metadata;
        tlv(metadata, LI_PYROWAVE_METADATA_HOST_PROCESSING_LATENCY,
            LI_PYROWAVE_METADATA_FLAG_PROTECTED | LI_PYROWAVE_METADATA_FLAG_RUNTIME | LI_PYROWAVE_METADATA_FLAG_OPTIONAL, {0, 12});
        tlv(metadata, fixture.type, protected_required, payload);
        const auto without_peak = metadata;
        if (fixture.hlgNominalPeakNits != 0) {
            tlv(metadata, LI_PYROWAVE_METADATA_HLG_NOMINAL_PEAK, protected_required, {3, 232});
            CHECK(!parse_dynamic_hdr_frame(without_peak.data(), without_peak.size(), fixture.format, 1920, 1080, scene));
        }
        CHECK(parse_dynamic_hdr_frame(metadata.data(), metadata.size(), fixture.format, 1920, 1080, scene));
        CHECK(scene.format == fixture.format);
        CHECK(scene.maximum_nits > 990 && scene.maximum_nits < 1010);
        CHECK(scene.average_nits > 90 && scene.average_nits < 110);
        CHECK(scene.hlg_nominal_peak_nits == fixture.hlgNominalPeakNits);
        if (fixture.hlgNominalPeakNits != 0) {
            auto invalid_peak = metadata;
            invalid_peak[invalid_peak.size() - 2] = 0;
            invalid_peak.back() = 0;
            dynamic_hdr_scene invalid;
            CHECK(!parse_dynamic_hdr_frame(invalid_peak.data(), invalid_peak.size(), fixture.format, 1920, 1080, invalid));
        }
        if (fixture.type == LI_PYROWAVE_METADATA_DOLBY_VISION_RPU) {
            CHECK(scene.scene_refresh);
            CHECK(scene.mastering_peak_nits == 1000);
            CHECK(scene.max_cll_nits == 1200);
            CHECK(scene.max_fall_nits == 200);
        }
        CHECK(build_dynamic_hdr_lut(scene, 500, lut));
        const auto first_lut = lut;
        auto changed_scene = scene;
        changed_scene.average_nits = 350;
        changed_scene.median_nits = 350;
        CHECK(build_dynamic_hdr_lut(changed_scene, 500, lut));
        CHECK(lut != first_lut); // Per-frame statistics actually change the mapping.
        CHECK(build_dynamic_hdr_lut(scene, 500, lut));
        CHECK(std::is_sorted(lut.begin(), lut.end()));
        CHECK(pq_to_nits(lut.back()) <= 501);
        for (std::size_t i = 0; i < lut.size(); ++i) {
            CHECK(std::isfinite(lut[i]) && lut[i] >= 0 && lut[i] <= static_cast<float>(i) / 255.0f);
        }
        CHECK(build_dynamic_hdr_lut(scene, 2000, lut));
        for (std::size_t i = 0; i < lut.size(); ++i) {
            CHECK(std::abs(lut[i] - std::min(static_cast<float>(i) / 255.0f, nits_to_pq(2000))) < 0.00001f);
        }

        for (std::size_t size = 0; size < without_peak.size(); ++size) {
            CHECK(!parse_dynamic_hdr_frame(without_peak.data(), size, fixture.format, 1920, 1080, scene));
        }
        CHECK(!parse_dynamic_hdr_frame(nullptr, 0, fixture.format, 1920, 1080, scene));
        CHECK(scene.format == 0); // A failed frame cannot leave the previous scene available.
        auto duplicate = metadata;
        tlv(duplicate, fixture.type, protected_required, payload);
        CHECK(!parse_dynamic_hdr_frame(duplicate.data(), duplicate.size(), fixture.format, 1920, 1080, scene));
        auto unsupported = metadata;
        tlv(unsupported, 0x7fff, protected_required, {1});
        CHECK(!parse_dynamic_hdr_frame(unsupported.data(), unsupported.size(), fixture.format, 1920, 1080, scene));
        auto optional = metadata;
        tlv(optional, 0x7fff, LI_PYROWAVE_METADATA_FLAG_OPTIONAL, {1});
        CHECK(parse_dynamic_hdr_frame(optional.data(), optional.size(), fixture.format, 1920, 1080, scene));
        auto corrupt = metadata;
        // Remove PROTECTED from the dynamic TLV (the runtime TLV is 10 bytes).
        corrupt[13] = LI_PYROWAVE_METADATA_FLAG_REQUIRED;
        CHECK(!parse_dynamic_hdr_frame(corrupt.data(), corrupt.size(), fixture.format, 1920, 1080, scene));
        corrupt = metadata;
        corrupt[18] ^= 0xff; // Corrupt the registered payload/NAL prefix.
        CHECK(!parse_dynamic_hdr_frame(corrupt.data(), corrupt.size(), fixture.format, 1920, 1080, scene));
        if (fixture.type == LI_PYROWAVE_METADATA_DOLBY_VISION_RPU) {
            corrupt = metadata;
            corrupt[18 + payload.size() - 3] ^= 1; // CRC mismatch, without changing the NAL prefix.
            CHECK(!parse_dynamic_hdr_frame(corrupt.data(), corrupt.size(), fixture.format, 1920, 1080, scene));
        }
        CHECK(!parse_dynamic_hdr_frame(metadata.data(), metadata.size(), fixture.type == 1 ? 2 : 1, 1920, 1080, scene));
    }

    scene = {};
    scene.format = 1;
    CHECK(build_dynamic_hdr_lut(scene, 500, lut)); // A valid black scene must remain black.
    CHECK(pq_to_nits(lut.front()) == 0);
    CHECK(!build_dynamic_hdr_lut(scene, 0, lut));
    CHECK(!build_dynamic_hdr_lut(scene, -1, lut));
    CHECK(!build_dynamic_hdr_lut(scene, std::numeric_limits<float>::infinity(), lut));
    scene.maximum_nits = std::numeric_limits<float>::quiet_NaN();
    CHECK(!build_dynamic_hdr_lut(scene, 500, lut));
    scene.maximum_nits = 1000;
    scene.average_nits = -1;
    CHECK(!build_dynamic_hdr_lut(scene, 500, lut));
    std::puts("PASS: generated HDR10+/Vivid/DV profiles, protected frame TLVs and scene-adaptive mapping");
    return 0;
}
