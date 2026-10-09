// JNI bridge for the :framegen module.
//
// Keep this file as a thin C ABI layer. Heavier Vulkan/LSFG work lives in
// framegen_pipeline.cpp so incremental Android builds do not need to pull the
// whole native pipeline through every small JNI edit.

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <pe-parse/parse.h>

#include "extract/trans.hpp"
#include "framegen_pipeline.hpp"
#include "pyrowave_vulkan_decoder.hpp"

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#include <memory>
#include <mutex>

#define LOG_TAG "Framegen"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

static std::atomic<uint64_t> g_frameCount{0};
static std::mutex g_pyrowaveMutex;

namespace {
constexpr uint32_t kGenerateShaderResourceId = 256;

struct LosslessProbeState {
    uint32_t rcdataCount{0};
    std::vector<uint8_t> generateShaderDxbc;
};

int onLosslessResource(void* context, const peparse::resource& res) {
    auto* state = static_cast<LosslessProbeState*>(context);
    if (state == nullptr || res.type != peparse::RT_RCDATA || res.buf == nullptr || res.buf->bufLen <= 0) {
        return 0;
    }

    state->rcdataCount++;
    if (res.name == kGenerateShaderResourceId) {
        state->generateShaderDxbc.resize(res.buf->bufLen);
        std::copy_n(res.buf->buf, res.buf->bufLen, state->generateShaderDxbc.data());
    }
    return 0;
}

std::string probeLosslessDll(const std::string& dllPath) {
    peparse::parsed_pe* dll = peparse::ParsePEFromFile(dllPath.c_str());
    if (dll == nullptr) {
        std::ostringstream oss;
        oss << "unable-to-open-dll err=" << peparse::GetPEErrString();
        throw std::runtime_error(oss.str());
    }

    LosslessProbeState state{};
    peparse::IterRsrc(dll, onLosslessResource, &state);
    peparse::DestructParsedPE(dll);

    if (state.generateShaderDxbc.empty()) {
        throw std::runtime_error("generate-shader-missing (resource #256)");
    }

    auto spirv = Extract::translateShader(state.generateShaderDxbc);
    std::ostringstream oss;
    oss << "lossless-dll-ok rcdata=" << state.rcdataCount
        << " generate_dxbc=" << state.generateShaderDxbc.size() << "B"
        << " spirv=" << spirv.size() << "B";
    return oss.str();
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSelfTest(JNIEnv *env, jobject /* thiz */) {
    const std::string msg = "framegen-native-ok";
    LOGI("nativeSelfTest -> %s", msg.c_str());
    return env->NewStringUTF(msg.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeOnFrameAvailable(
        JNIEnv *env, jclass /* clazz */,
        jobject jHwBuffer, jint width, jint height, jint format,
        jlong timestampNs, jfloat observedInputFps) {
    if (jHwBuffer == nullptr) {
        LOGW("nativeOnFrameAvailable: null HardwareBuffer");
        return 0;
    }

    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jHwBuffer);
    if (ahb == nullptr) {
        LOGE("AHardwareBuffer_fromHardwareBuffer returned NULL");
        return 0;
    }

    (void)FramegenPipeline::ensureContextBootstrapped(ahb, width, height, format);

    const uint64_t n = g_frameCount.fetch_add(1, std::memory_order_relaxed) + 1;

    (void)FramegenPipeline::probeImportDecoderAhb(
        ahb,
        static_cast<int64_t>(timestampNs),
        static_cast<float>(observedInputFps));

    if (n == 1 || (n % 300) == 0) {
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(ahb, &desc);
        LOGI("frame#%llu ahb=%p reader=%dx%d/fmt=%d  ahb=%ux%u/fmt=0x%x/usage=0x%llx/layers=%u/stride=%u  ts=%lld observedFps=%.1f",
             (unsigned long long)n, ahb,
             width, height, format,
             desc.width, desc.height, desc.format,
             (unsigned long long)desc.usage,
             desc.layers, desc.stride,
             (long long)timestampNs,
             static_cast<double>(observedInputFps));
    }

    return static_cast<jlong>(n);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeResetFrameCounter(
        JNIEnv * /* env */, jclass /* clazz */) {
    const uint64_t prev = g_frameCount.exchange(0, std::memory_order_relaxed);
    FramegenPipeline::reset();
    LOGI("frame counter reset (was %llu)", (unsigned long long)prev);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeGetStats(
        JNIEnv *env, jclass /* clazz */) {
    const FramegenPipeline::StatsSnapshot stats = FramegenPipeline::getStatsSnapshot();
    jlong values[] = {
        static_cast<jlong>(stats.realFrames),
        static_cast<jlong>(stats.interpolatedFrames),
        static_cast<jlong>(stats.bypassFrames),
        static_cast<jlong>(stats.realOnlyFrames),
        static_cast<jlong>(stats.presenterDrops),
        static_cast<jlong>(stats.fallbackFrames),
        static_cast<jlong>(stats.queueDepth),
        static_cast<jlong>(stats.outputFrameRate),
        static_cast<jlong>(stats.inputFpsTenths),
        static_cast<jlong>(stats.mode),
        static_cast<jlong>(stats.lastLsfgWaitMs),
        static_cast<jlong>(stats.lastBlitMs),
    };
    constexpr jsize valueCount = static_cast<jsize>(sizeof(values) / sizeof(values[0]));
    jlongArray result = env->NewLongArray(valueCount);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, valueCount, values);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePrewarmContext(
        JNIEnv * /* env */, jclass /* clazz */, jint width, jint height) {
    return FramegenPipeline::prewarmContext(
        static_cast<int32_t>(width),
        static_cast<int32_t>(height)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSetLosslessDllPath(
        JNIEnv *env, jclass /* clazz */, jstring jDllPath) {
    if (jDllPath == nullptr) {
        LOGW("nativeSetLosslessDllPath: null path");
        return;
    }

    const char* rawPath = env->GetStringUTFChars(jDllPath, nullptr);
    if (rawPath == nullptr) {
        LOGE("nativeSetLosslessDllPath: GetStringUTFChars failed");
        return;
    }

    setenv("LSFG_DLL_PATH_UNIX", rawPath, 1); // NOLINT(concurrency-mt-unsafe)
    LOGI("nativeSetLosslessDllPath -> %s", rawPath);
    env->ReleaseStringUTFChars(jDllPath, rawPath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSetHdrMode(
        JNIEnv * /*env*/, jclass /* clazz */, jint mode, jboolean fullRange) {
    FramegenPipeline::setHdrMode(
        static_cast<int32_t>(mode),
        fullRange == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSetOutputFrameRate(
        JNIEnv * /*env*/, jclass /* clazz */, jint fps) {
    FramegenPipeline::setOutputFrameRate(static_cast<int32_t>(fps));
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSetTuningConfig(
        JNIEnv * /*env*/, jclass /* clazz */,
        jint internalWidth, jint presentMode, jint slowLsfgThresholdMs, jint presentQueueMax,
        jboolean allowHighInputBypass) {
    FramegenPipeline::setTuningConfig(
        static_cast<int32_t>(internalWidth),
        static_cast<int32_t>(presentMode),
        static_cast<int32_t>(slowLsfgThresholdMs),
        static_cast<int32_t>(presentQueueMax),
        allowHighInputBypass == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSetOutputSurface(
        JNIEnv *env, jclass /* clazz */, jobject jSurface) {
    ANativeWindow* win = nullptr;
    if (jSurface != nullptr) {
        win = ANativeWindow_fromSurface(env, jSurface);
        if (win == nullptr) {
            LOGE("nativeSetOutputSurface: ANativeWindow_fromSurface returned NULL");
            return;
        }
    }
    // FramegenPipeline owns and releases the previous native window reference.
    FramegenPipeline::setOutputWindow(win);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeProbeLosslessDll(
        JNIEnv *env, jobject /* thiz */, jstring jDllPath) {
    if (jDllPath == nullptr) {
        const std::string msg = "dll-path-null";
        LOGW("nativeProbeLosslessDll: %s", msg.c_str());
        return env->NewStringUTF(msg.c_str());
    }

    const char* rawPath = env->GetStringUTFChars(jDllPath, nullptr);
    if (rawPath == nullptr) {
        const std::string msg = "dll-path-utf8-failed";
        LOGE("nativeProbeLosslessDll: %s", msg.c_str());
        return env->NewStringUTF(msg.c_str());
    }

    std::string result;
    try {
        result = probeLosslessDll(rawPath);
        setenv("LSFG_DLL_PATH_UNIX", rawPath, 1); // NOLINT(concurrency-mt-unsafe)
        LOGI("nativeProbeLosslessDll(%s) -> %s", rawPath, result.c_str());
    } catch (const std::exception& e) {
        result = std::string("lossless-dll-probe-failed: ") + e.what();
        LOGE("nativeProbeLosslessDll(%s) failed: %s", rawPath, e.what());
    }

    env->ReleaseStringUTFChars(jDllPath, rawPath);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveIsAvailableFor(
        JNIEnv *, jclass, jint width, jint height, jint hdrMode) {
    return FramegenPipeline::PyrowaveVulkanDecoder::available(
               static_cast<int>(width), static_cast<int>(height), static_cast<int>(hdrMode))
        ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveCreate(
        JNIEnv *, jclass, jint width, jint height, jint hdrMode, jboolean fullRange) {
    std::lock_guard<std::mutex> lock(g_pyrowaveMutex);
    auto decoder = std::make_unique<FramegenPipeline::PyrowaveVulkanDecoder>();
    if (!decoder->create(static_cast<int>(width), static_cast<int>(height),
                         static_cast<int>(hdrMode), fullRange == JNI_TRUE)) {
        return 0;
    }
    // The object is kept independent from the framegen context. This prevents
    // a framegen reset from invalidating an active PyroWave stream.
    return reinterpret_cast<jlong>(decoder.release());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveSetSurface(
        JNIEnv *env, jclass, jlong handle, jobject surface) {
    auto *decoder = reinterpret_cast<FramegenPipeline::PyrowaveVulkanDecoder *>(handle);
    if (decoder == nullptr) return JNI_FALSE;
    ANativeWindow *window = nullptr;
    try {
        window = surface == nullptr ? nullptr : ANativeWindow_fromSurface(env, surface);
        const bool ok = decoder->setSurface(window);
        if (window != nullptr) ANativeWindow_release(window);
        if (!ok) LOGW("PyroWave Vulkan decoder could not bind the output Surface");
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (window != nullptr) ANativeWindow_release(window);
        LOGW("PyroWave Vulkan Surface binding raised an exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveSetHdrMetadata(
        JNIEnv *env, jclass, jlong handle, jboolean enabled, jbyteArray metadata) {
    auto *decoder = reinterpret_cast<FramegenPipeline::PyrowaveVulkanDecoder *>(handle);
    if (decoder == nullptr) return JNI_FALSE;
    jbyte *bytes = nullptr;
    try {
        if (metadata == nullptr) {
            return decoder->setHdrMetadata(enabled == JNI_TRUE, nullptr, 0) ? JNI_TRUE : JNI_FALSE;
        }
        const auto length = env->GetArrayLength(metadata);
        if (length <= 0) {
            return decoder->setHdrMetadata(enabled == JNI_TRUE, nullptr, 0) ? JNI_TRUE : JNI_FALSE;
        }
        bytes = env->GetByteArrayElements(metadata, nullptr);
        if (bytes == nullptr) return JNI_FALSE;
        const bool ok = decoder->setHdrMetadata(
                enabled == JNI_TRUE,
                reinterpret_cast<const std::uint8_t *>(bytes),
                static_cast<std::size_t>(length));
        env->ReleaseByteArrayElements(metadata, bytes, JNI_ABORT);
        return ok ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        if (bytes != nullptr) env->ReleaseByteArrayElements(metadata, bytes, JNI_ABORT);
        LOGW("PyroWave Vulkan HDR metadata update raised an exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveSubmit(
        JNIEnv *env, jclass, jlong handle, jbyteArray data, jint length, jbyteArray metadata) {
    auto *decoder = reinterpret_cast<FramegenPipeline::PyrowaveVulkanDecoder *>(handle);
    if (decoder == nullptr || data == nullptr || length <= 0 || env->GetArrayLength(data) < length) return -1;
    jbyte *bytes = nullptr;
    jbyte *metadataBytes = nullptr;
    try {
        const auto metadataLength = metadata != nullptr ? env->GetArrayLength(metadata) : 0;
        if (metadataLength > 65535) return -1;
        if (metadataLength > 0) {
            metadataBytes = env->GetByteArrayElements(metadata, nullptr);
            if (metadataBytes == nullptr) return -1;
        }
        bytes = env->GetByteArrayElements(data, nullptr);
        if (bytes == nullptr) {
            if (metadataBytes != nullptr) env->ReleaseByteArrayElements(metadata, metadataBytes, JNI_ABORT);
            return -1;
        }
        const int result = decoder->submit(
            reinterpret_cast<const std::uint8_t *>(bytes), static_cast<std::size_t>(length),
            reinterpret_cast<const std::uint8_t *>(metadataBytes), static_cast<std::size_t>(metadataLength));
        if (metadataBytes != nullptr) env->ReleaseByteArrayElements(metadata, metadataBytes, JNI_ABORT);
        env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        return result;
    } catch (...) {
        if (metadataBytes != nullptr) env->ReleaseByteArrayElements(metadata, metadataBytes, JNI_ABORT);
        if (bytes != nullptr) env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
        LOGW("PyroWave Vulkan submit raised an exception");
        return -1;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveSetDynamicHdr(
        JNIEnv *, jclass, jlong handle, jint format, jfloat targetPeakNits) {
    auto *decoder = reinterpret_cast<FramegenPipeline::PyrowaveVulkanDecoder *>(handle);
    if (decoder == nullptr) return JNI_FALSE;
    try {
        return decoder->setDynamicHdr(format, targetPeakNits) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        LOGW("PyroWave dynamic HDR configuration raised an exception");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveGetLastTimings(
        JNIEnv *, jclass, jlong handle) {
    auto *decoder = reinterpret_cast<FramegenPipeline::PyrowaveVulkanDecoder *>(handle);
    if (decoder == nullptr) return 0;
    try {
        return static_cast<jlong>(decoder->getLastTimingsPacked());
    } catch (...) {
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativePyrowaveDestroy(
        JNIEnv *, jclass, jlong handle) {
    try {
        delete reinterpret_cast<FramegenPipeline::PyrowaveVulkanDecoder *>(handle);
    } catch (...) {
        LOGW("PyroWave Vulkan decoder destruction raised an exception");
    }
}
