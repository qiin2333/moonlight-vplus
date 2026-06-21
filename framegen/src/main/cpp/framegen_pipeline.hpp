#pragma once

#include <android/hardware_buffer.h>
#include <android/native_window.h>

#include <cstdint>

namespace FramegenPipeline {

struct StatsSnapshot {
    uint64_t realFrames{0};
    uint64_t interpolatedFrames{0};
    uint64_t bypassFrames{0};
    uint64_t realOnlyFrames{0};
    uint64_t presenterDrops{0};
    uint64_t fallbackFrames{0};
    int32_t queueDepth{0};
    int32_t outputFrameRate{0};
    int32_t inputFpsTenths{0};
    int32_t mode{0};
    int32_t lastLsfgWaitMs{0};
    int32_t lastBlitMs{0};
};

bool ensureVulkanAhbReady(AHardwareBuffer* ahb, int width, int height, int format);
bool ensureContextBootstrapped(AHardwareBuffer* decoderAhb, int width, int height, int format);
bool probeImportDecoderAhb(AHardwareBuffer* decoderAhb, int64_t timestampNs, float observedInputFps);
bool prewarmContext(int width, int height);
StatsSnapshot getStatsSnapshot();

void reset();
void setHdrMode(int32_t mode, bool fullRange);
void setOutputFrameRate(int32_t fps);
void setTuningConfig(int32_t internalWidth,
                     int32_t presentMode,
                     int32_t slowLsfgThresholdMs,
                     int32_t presentQueueMax,
                     bool allowHighInputBypass);
void setOutputWindow(ANativeWindow* nativeWindow);

} // namespace FramegenPipeline
