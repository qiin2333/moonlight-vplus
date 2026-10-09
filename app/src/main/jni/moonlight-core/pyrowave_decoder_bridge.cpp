/*
 * SPDX-License-Identifier: GPL-3.0-only
 * Copyright (c) 2026 Moonlight V+ contributors
 *
 * PyroWave decoder bridge for Android. This module keeps the SDR CPU staging
 * fallback, while the preferred HDR/Surface path is implemented by the
 * optional framegen Vulkan library. The codec library is loaded at runtime so
 * an APK built without it keeps the normal MediaCodec path and does not
 * advertise the format.
 */
#include <jni.h>

#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <dlfcn.h>
#include <vulkan/vulkan.h>

#include "pyrowave_api.h"
#include "pyrowave_capabilities.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <memory>
#include <mutex>
#include <new>
#include <utility>
#include <unordered_map>
#include <vector>

namespace {

constexpr int PYROWAVE_MAX_WIDTH = 4096;
constexpr int PYROWAVE_MAX_HEIGHT = 4096;
constexpr std::uint64_t PYROWAVE_MAX_PIXELS = 4096ull * 2160ull;

bool valid_dimensions(int width, int height) noexcept {
  if (width <= 0 || height <= 0 || (width & 1) != 0 || (height & 1) != 0 ||
      width > PYROWAVE_MAX_WIDTH || height > PYROWAVE_MAX_HEIGHT) {
    return false;
  }
  return static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) <=
         PYROWAVE_MAX_PIXELS;
}

using create_device_fn = pyrowave_result (*)(pyrowave_device *);
using destroy_device_fn = void (*)(pyrowave_device);
using get_api_version_fn = void (*)(uint32_t *, uint32_t *, uint32_t *);
using prefers_fragment_path_fn = bool (*)(pyrowave_device);
using create_decoder_fn = pyrowave_result (*)(const pyrowave_decoder_create_info *, pyrowave_decoder *);
using clear_decoder_fn = void (*)(pyrowave_decoder);
using destroy_decoder_fn = void (*)(pyrowave_decoder);
using push_packet_fn = pyrowave_result (*)(pyrowave_decoder, const void *, size_t);
using is_ready_fn = bool (*)(pyrowave_decoder, bool);
using decode_cpu_fn = pyrowave_result (*)(pyrowave_decoder, const pyrowave_cpu_buffer *);
using get_color_metadata_fn = bool (*)(pyrowave_decoder, pyrowave_color_metadata *);

struct api_t {
  void *library = nullptr;
  get_api_version_fn get_api_version = nullptr;
  create_device_fn create_device = nullptr;
  destroy_device_fn destroy_device = nullptr;
  prefers_fragment_path_fn prefers_fragment_path = nullptr;
  create_decoder_fn create_decoder = nullptr;
  clear_decoder_fn clear_decoder = nullptr;
  destroy_decoder_fn destroy_decoder = nullptr;
  push_packet_fn push_packet = nullptr;
  is_ready_fn is_ready = nullptr;
  decode_cpu_fn decode_cpu = nullptr;
  get_color_metadata_fn get_color_metadata = nullptr;

  bool load() {
    if (library != nullptr) {
      return get_api_version != nullptr && create_device != nullptr &&
          prefers_fragment_path != nullptr;
    }

    // The optional library is packaged as a normal Android JNI dependency.
    // Do not search arbitrary filesystem locations: loading only by the
    // soname keeps the trust boundary inside the APK/native library namespace.
    library = dlopen("libpyrowave-shared.so", RTLD_NOW | RTLD_LOCAL);
    if (library == nullptr) {
      return false;
    }

    get_api_version = reinterpret_cast<get_api_version_fn>(dlsym(library, "pyrowave_get_api_version"));
    create_device = reinterpret_cast<create_device_fn>(dlsym(library, "pyrowave_create_default_device"));
    destroy_device = reinterpret_cast<destroy_device_fn>(dlsym(library, "pyrowave_device_destroy"));
    prefers_fragment_path = reinterpret_cast<prefers_fragment_path_fn>(
        dlsym(library, "pyrowave_decoder_device_prefers_fragment_path"));
    create_decoder = reinterpret_cast<create_decoder_fn>(dlsym(library, "pyrowave_decoder_create"));
    clear_decoder = reinterpret_cast<clear_decoder_fn>(dlsym(library, "pyrowave_decoder_clear"));
    destroy_decoder = reinterpret_cast<destroy_decoder_fn>(dlsym(library, "pyrowave_decoder_destroy"));
    push_packet = reinterpret_cast<push_packet_fn>(dlsym(library, "pyrowave_decoder_push_packet"));
    is_ready = reinterpret_cast<is_ready_fn>(dlsym(library, "pyrowave_decoder_decode_is_ready"));
    decode_cpu = reinterpret_cast<decode_cpu_fn>(dlsym(library, "pyrowave_decoder_decode_cpu_buffer_synchronous"));
    get_color_metadata = reinterpret_cast<get_color_metadata_fn>(dlsym(library, "pyrowave_decoder_get_color_metadata"));

    uint32_t apiMajor = 0;
    uint32_t apiMinor = 0;
    uint32_t apiPatch = 0;
    if (get_api_version != nullptr) {
      get_api_version(&apiMajor, &apiMinor, &apiPatch);
    }
    if (get_api_version == nullptr || apiMajor != PYROWAVE_API_VERSION_MAJOR ||
        apiMinor != PYROWAVE_API_VERSION_MINOR || apiPatch != PYROWAVE_API_VERSION_PATCH ||
        create_device == nullptr ||
        destroy_device == nullptr || prefers_fragment_path == nullptr || create_decoder == nullptr ||
        clear_decoder == nullptr ||
        destroy_decoder == nullptr || push_packet == nullptr || is_ready == nullptr || decode_cpu == nullptr ||
        get_color_metadata == nullptr) {
      dlclose(library);
      library = nullptr;
      get_api_version = nullptr;
      create_device = nullptr;
      destroy_device = nullptr;
      prefers_fragment_path = nullptr;
      create_decoder = nullptr;
      clear_decoder = nullptr;
      destroy_decoder = nullptr;
      push_packet = nullptr;
      is_ready = nullptr;
      decode_cpu = nullptr;
      get_color_metadata = nullptr;
      return false;
    }
    return true;
  }

  ~api_t() {
    if (library != nullptr) {
      dlclose(library);
    }
  }
};

struct decoder_t {
  api_t api;
  pyrowave_device device = nullptr;
  pyrowave_decoder decoder = nullptr;
  ANativeWindow *window = nullptr;
  int width = 0;
  int height = 0;
  bool full_range = false;
  int hdr_mode = 0;
  bool geometry_set = false;
  std::uint64_t last_decode_time_us = 0;
  std::uint64_t last_present_time_us = 0;
  std::vector<std::uint8_t> y;
  std::vector<std::uint8_t> u;
  std::vector<std::uint8_t> v;
  std::mutex mutex;

  ~decoder_t() {
    std::lock_guard<std::mutex> lock { mutex };
    if (window != nullptr) {
      ANativeWindow_release(window);
      window = nullptr;
    }
    if (decoder != nullptr && api.destroy_decoder != nullptr) {
      try {
        api.destroy_decoder(decoder);
      } catch (...) {
        // Destruction must not let a vendor exception escape a JNI call.
      }
      decoder = nullptr;
    }
    if (device != nullptr && api.destroy_device != nullptr) {
      try {
        api.destroy_device(device);
      } catch (...) {
        // Destruction must not let a vendor exception escape a JNI call.
      }
      device = nullptr;
    }
  }

  bool present() {
    if (window == nullptr) {
      // A decoded frame without an attached Surface is not a successful
      // presentation. Let the session keep its recovery accounting intact;
      // lifecycle code will rebind the Surface or stop the stream.
      return false;
    }

    if (!geometry_set) {
      if (ANativeWindow_setBuffersGeometry(window, width, height, WINDOW_FORMAT_RGBA_8888) != 0) {
        return false;
      }
      geometry_set = true;
    }
    ANativeWindow_Buffer buffer {};
    if (ANativeWindow_lock(window, &buffer, nullptr) != 0) {
      return false;
    }
    if (buffer.bits == nullptr) {
      ANativeWindow_unlockAndPost(window);
      return false;
    }

    const auto copy_width = std::min(width, buffer.width);
    const auto copy_height = std::min(height, buffer.height);
    if (copy_width <= 0 || copy_height <= 0 || buffer.stride < copy_width) {
      ANativeWindow_unlockAndPost(window);
      return false;
    }
    const auto dst_stride = static_cast<std::size_t>(buffer.stride) * 4u;
    for (int row = 0; row < copy_height; ++row) {
      auto *dst = static_cast<std::uint8_t *>(buffer.bits) + static_cast<std::size_t>(row) * dst_stride;
      for (int col = 0; col < copy_width; ++col) {
        const auto y_index = static_cast<std::size_t>(row) * width + col;
        const auto uv_index = static_cast<std::size_t>(row / 2) * (width / 2) + (col / 2);
        const float luma = full_range
          ? static_cast<float>(y[y_index])
          : (static_cast<float>(y[y_index]) - 16.0f) * (255.0f / 219.0f);
        const float cb_scale = full_range ? 1.0f : (255.0f / 224.0f);
        const float cb = (static_cast<float>(u[uv_index]) - 128.0f) * cb_scale;
        const float cr = (static_cast<float>(v[uv_index]) - 128.0f) * cb_scale;
        const auto red = static_cast<std::uint8_t>(std::clamp(std::lround(luma + 1.5748f * cr), 0l, 255l));
        const auto green = static_cast<std::uint8_t>(std::clamp(std::lround(luma - 0.1873f * cb - 0.4681f * cr), 0l, 255l));
        const auto blue = static_cast<std::uint8_t>(std::clamp(std::lround(luma + 1.8556f * cb), 0l, 255l));
        dst[static_cast<std::size_t>(col) * 4u + 0] = red;
        dst[static_cast<std::size_t>(col) * 4u + 1] = green;
        dst[static_cast<std::size_t>(col) * 4u + 2] = blue;
        dst[static_cast<std::size_t>(col) * 4u + 3] = 255;
      }
    }
    return ANativeWindow_unlockAndPost(window) == 0;
  }
};

decoder_t *from_handle(jlong handle) {
  return reinterpret_cast<decoder_t *>(handle);
}

std::mutex availability_mutex;
std::unordered_map<std::uint64_t, bool> availability_dimensions;

bool can_create_decoder(api_t &api, int width, int height) {
  if (!valid_dimensions(width, height)) {
    return false;
  }

  pyrowave_device device = nullptr;
  pyrowave_decoder decoder = nullptr;
  try {
    const auto device_result = api.create_device(&device);
    if (device_result != PYROWAVE_SUCCESS || device == nullptr) {
      if (device != nullptr) {
        try {
          api.destroy_device(device);
        } catch (...) {
        }
      }
      return false;
    }
    const pyrowave_decoder_create_info info {
      .device = device,
      .width = width,
      .height = height,
      .chroma = PYROWAVE_CHROMA_SUBSAMPLING_420,
      .fragment_path = api.prefers_fragment_path(device),
    };
    const auto result = api.create_decoder(&info, &decoder);
    const bool created = result == PYROWAVE_SUCCESS && decoder != nullptr;
    bool cleanup_succeeded = true;
    if (decoder != nullptr) {
      try {
        api.destroy_decoder(decoder);
      } catch (...) {
        cleanup_succeeded = false;
      }
    }
    try {
      api.destroy_device(device);
    } catch (...) {
      cleanup_succeeded = false;
    }
    return created && cleanup_succeeded;
  } catch (...) {
    if (decoder != nullptr) {
      try {
        api.destroy_decoder(decoder);
      } catch (...) {
      }
    }
    if (device != nullptr) {
      try {
        api.destroy_device(device);
      } catch (...) {
      }
    }
    return false;
  }
}

}  // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyrowaveGetCapabilities(JNIEnv *env, jclass) {
  bool runtime_available = false;
  try {
    api_t api;
    runtime_available = api.load();
  } catch (...) {
  }
  return query_pyrowave_capabilities(env, runtime_available);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyrowaveIsAvailableFor(
    JNIEnv *, jclass, jint width, jint height) {
  if (!valid_dimensions(width, height)) {
    return JNI_FALSE;
  }
  const auto key = (static_cast<std::uint64_t>(static_cast<std::uint32_t>(width)) << 32) |
                   static_cast<std::uint32_t>(height);
  std::lock_guard<std::mutex> lock { availability_mutex };
  if (const auto cached = availability_dimensions.find(key); cached != availability_dimensions.end()) {
    return cached->second ? JNI_TRUE : JNI_FALSE;
  }
  bool available = false;
  try {
    api_t api;
    available = api.load() && can_create_decoder(api, width, height);
    availability_dimensions.emplace(key, available);
  } catch (...) {
    // Availability is a probe, not a correctness requirement. If the cache
    // cannot grow, return this result without letting the exception cross JNI.
  }
  return available ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyrowaveCreate(JNIEnv *, jclass, jint width, jint height, jint hdr_mode, jboolean full_range) {
  // This is the SDR CPU-staging fallback. HDR must stay on the Vulkan
  // Surface path, which preserves 10-bit planes and the negotiated transfer.
  if (!valid_dimensions(width, height) || hdr_mode != 0) {
    return 0;
  }

  try {
    auto decoder = std::make_unique<decoder_t>();
    if (!decoder->api.load()) {
      return 0;
    }

    if (decoder->api.create_device(&decoder->device) != PYROWAVE_SUCCESS || decoder->device == nullptr) {
      return 0;
    }
    const pyrowave_decoder_create_info info {
      .device = decoder->device,
      .width = width,
      .height = height,
      .chroma = PYROWAVE_CHROMA_SUBSAMPLING_420,
      .fragment_path = decoder->api.prefers_fragment_path(decoder->device),
    };
    if (decoder->api.create_decoder(&info, &decoder->decoder) != PYROWAVE_SUCCESS || decoder->decoder == nullptr) {
      return 0;
    }

    decoder->width = width;
    decoder->height = height;
    decoder->hdr_mode = hdr_mode;
    decoder->full_range = full_range;
    const auto luma_size = static_cast<std::size_t>(width) * static_cast<std::size_t>(height);
    const auto chroma_size = static_cast<std::size_t>(width / 2) * static_cast<std::size_t>(height / 2);
    decoder->y.resize(luma_size);
    decoder->u.resize(chroma_size);
    decoder->v.resize(chroma_size);
    return reinterpret_cast<jlong>(decoder.release());
  } catch (...) {
    // Never allow allocation or runtime decoder failures to cross the JNI
    // boundary. The caller will treat a zero handle as unavailable and use a
    // legacy decoder on the next connection.
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyrowaveSetSurface(JNIEnv *env, jclass, jlong handle, jobject surface) {
  auto *decoder = from_handle(handle);
  if (decoder == nullptr) {
    return;
  }
  try {
    std::lock_guard<std::mutex> lock { decoder->mutex };
    auto *next_window = surface == nullptr ? nullptr : ANativeWindow_fromSurface(env, surface);
    if (decoder->window != nullptr) {
      ANativeWindow_release(decoder->window);
    }
    decoder->window = next_window;
    decoder->geometry_set = false;
  } catch (...) {
    // Preserve any pending Java exception from ANativeWindow_fromSurface;
    // never throw a native exception across the JNI boundary.
  }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyrowaveSubmit(JNIEnv *env, jclass, jlong handle, jbyteArray data, jint length) {
  auto *decoder = from_handle(handle);
  if (decoder == nullptr || data == nullptr || length <= 0) {
    return -1;
  }
  jbyte *bytes = nullptr;
  try {
    std::lock_guard<std::mutex> lock { decoder->mutex };
    const auto array_length = env->GetArrayLength(data);
    if (length > array_length) {
      return -1;
    }
    bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
      return -1;
    }
    const auto decode_start = std::chrono::steady_clock::now();
    const auto push_result = decoder->api.push_packet(
        decoder->decoder, bytes, static_cast<std::size_t>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    bytes = nullptr;
    if (push_result != PYROWAVE_SUCCESS) {
      return -1;
    }
    if (!decoder->api.is_ready(decoder->decoder, false)) {
      // Keep the decoder and Surface alive so the compositor continues to
      // show the last successfully presented frame.
      decoder->api.clear_decoder(decoder->decoder);
      return PYROWAVE_SUBMIT_FRAME_DROPPED;
    }
    pyrowave_color_metadata metadata {};
    if (!decoder->api.get_color_metadata(decoder->decoder, &metadata)) {
      return -1;
    }
    const bool expected_hdr = decoder->hdr_mode != 0;
    const auto expected_transfer = decoder->hdr_mode == 2
      ? PYROWAVE_TRANSFER_HLG
      : PYROWAVE_TRANSFER_PQ;
    if ((expected_hdr && (metadata.primaries != PYROWAVE_COLOR_PRIMARIES_BT2020 ||
                          metadata.transfer != expected_transfer ||
                          metadata.transform != PYROWAVE_YCBCR_BT2020)) ||
        (!expected_hdr && (metadata.primaries != PYROWAVE_COLOR_PRIMARIES_BT709 ||
                           metadata.transfer != PYROWAVE_TRANSFER_BT709 ||
                           metadata.transform != PYROWAVE_YCBCR_BT709)) ||
        metadata.range != (decoder->full_range ? PYROWAVE_YCBCR_FULL : PYROWAVE_YCBCR_LIMITED)) {
      return -1;
    }

    pyrowave_cpu_buffer output {
      .data = { decoder->y.data(), decoder->u.data(), decoder->v.data() },
      .row_stride_in_bytes = { static_cast<std::size_t>(decoder->width),
                               static_cast<std::size_t>(decoder->width / 2),
                               static_cast<std::size_t>(decoder->width / 2) },
      .plane_size_in_bytes = { decoder->y.size(), decoder->u.size(), decoder->v.size() },
      .width = decoder->width,
      .height = decoder->height,
      .format = PYROWAVE_CPU_BUFFER_FORMAT_YUV420P,
    };
    if (decoder->api.decode_cpu(decoder->decoder, &output) != PYROWAVE_SUCCESS) {
      return -1;
    }
    const auto decode_end = std::chrono::steady_clock::now();
    const auto present_start = decode_end;
    const bool presented = decoder->present();
    const auto present_end = std::chrono::steady_clock::now();
    decoder->last_decode_time_us = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(decode_end - decode_start).count());
    decoder->last_present_time_us = static_cast<std::uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(present_end - present_start).count());
    return presented ? 0 : -1;
  } catch (...) {
    if (bytes != nullptr) {
      env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    }
    // Do not clear a pending Java exception. The Kotlin wrapper will observe
    // it and route the call through the same bounded recovery path.
    return -1;
  }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyrowaveGetLastTimings(
    JNIEnv *, jclass, jlong handle) {
  auto *decoder = from_handle(handle);
  if (decoder == nullptr) return 0;
  try {
    std::lock_guard<std::mutex> lock { decoder->mutex };
    return static_cast<jlong>(
        ((decoder->last_decode_time_us & 0xffffffffULL) << 32) |
        (decoder->last_present_time_us & 0xffffffffULL));
  } catch (...) {
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_nvstream_jni_MoonBridge_pyrowaveDestroy(JNIEnv *, jclass, jlong handle) {
  try {
    delete from_handle(handle);
  } catch (...) {
    // Cleanup must not propagate a vendor exception through JNI.
  }
}
