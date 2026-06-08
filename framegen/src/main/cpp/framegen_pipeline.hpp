#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>

#include <cstdint>

namespace FramegenPipeline {

bool ensureVulkanAhbReady(AHardwareBuffer* ahb, int width, int height, int format);
bool ensureContextBootstrapped(AHardwareBuffer* decoderAhb, int width, int height, int format);
bool probeImportDecoderAhb(AHardwareBuffer* decoderAhb, int64_t timestampNs, float observedInputFps);
bool prewarmContext(int width, int height);

void reset();
void setHdrEnabled(bool enabled);
void setOutputFrameRate(int32_t fps);
void setTuningConfig(int32_t internalWidth,
                     int32_t presentMode,
                     int32_t slowLsfgThresholdMs,
                     int32_t presentQueueMax);
void setOutputWindow(ANativeWindow* nativeWindow);

} // namespace FramegenPipeline
