/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) 2026 Moonlight V+ contributors
 *
 * Sunshine's SS_HDR_METADATA is a fixed little-endian control-channel
 * payload.  Keep this presentation metadata separate from PyroWave's
 * pyrowave_color_metadata, which describes the decoded YCbCr stream only.
 */
#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cmath>

namespace moonlight::pyrowave {

struct hdr_point {
  float x = 0.0F;
  float y = 0.0F;
};

struct hdr_static_metadata {
  std::array<hdr_point, 3> display_primaries{};
  hdr_point white_point{};
  float max_display_luminance = 0.0F;
  float min_display_luminance = 0.0F;
  float max_content_light_level = 0.0F;
  float max_frame_average_light_level = 0.0F;
  float max_full_frame_luminance = 0.0F;
};

inline bool finite_non_negative(float value) noexcept {
  return std::isfinite(value) && value >= 0.0F;
}

inline bool valid_hdr_static_metadata(const hdr_static_metadata &metadata,
                                      bool require_mastering_luminance) noexcept {
  for (const auto &point : metadata.display_primaries) {
    if (!std::isfinite(point.x) || !std::isfinite(point.y) || point.x <= 0.0F ||
        point.y <= 0.0F || point.x > 1.0F || point.y > 1.0F) {
      return false;
    }
  }
  if (!std::isfinite(metadata.white_point.x) || !std::isfinite(metadata.white_point.y) ||
      metadata.white_point.x <= 0.0F || metadata.white_point.y <= 0.0F ||
      metadata.white_point.x > 1.0F || metadata.white_point.y > 1.0F ||
      !finite_non_negative(metadata.min_display_luminance) ||
      !finite_non_negative(metadata.max_content_light_level) ||
      !finite_non_negative(metadata.max_frame_average_light_level) ||
      !finite_non_negative(metadata.max_full_frame_luminance)) {
    return false;
  }
  if (require_mastering_luminance) {
    return std::isfinite(metadata.max_display_luminance) &&
           metadata.max_display_luminance > 0.0F &&
           metadata.min_display_luminance <= metadata.max_display_luminance;
  }
  return finite_non_negative(metadata.max_display_luminance);
}

inline bool parse_ss_hdr_metadata(const std::uint8_t *bytes, std::size_t size,
                                  hdr_static_metadata &metadata) noexcept {
  // SS_HDR_METADATA consists of 13 little-endian uint16 values: eight values
  // for RGB primaries/white point and five luminance/content-light values.
  constexpr std::size_t kFieldCount = 13;
  constexpr std::size_t kPayloadSize = kFieldCount * sizeof(std::uint16_t);
  if (bytes == nullptr || size < kPayloadSize) return false;

  std::array<std::uint16_t, kFieldCount> fields{};
  for (std::size_t i = 0; i < fields.size(); ++i) {
    fields[i] = static_cast<std::uint16_t>(bytes[i * 2]) |
                (static_cast<std::uint16_t>(bytes[i * 2 + 1]) << 8);
  }
  constexpr float kCoordinateScale = 50000.0F;
  for (std::size_t i = 0; i < 3; ++i) {
    const auto x = fields[i * 2];
    const auto y = fields[i * 2 + 1];
    if (x > 50000 || y > 50000) return false;
    metadata.display_primaries[i] = { static_cast<float>(x) / kCoordinateScale,
                                      static_cast<float>(y) / kCoordinateScale };
  }
  if (fields[6] > 50000 || fields[7] > 50000) return false;
  metadata.white_point = { static_cast<float>(fields[6]) / kCoordinateScale,
                           static_cast<float>(fields[7]) / kCoordinateScale };
  metadata.max_display_luminance = static_cast<float>(fields[8]);
  metadata.min_display_luminance = static_cast<float>(fields[9]) / 10000.0F;
  metadata.max_content_light_level = static_cast<float>(fields[10]);
  metadata.max_frame_average_light_level = static_cast<float>(fields[11]);
  metadata.max_full_frame_luminance = static_cast<float>(fields[12]);
  return valid_hdr_static_metadata(metadata, true);
}

}  // namespace moonlight::pyrowave
