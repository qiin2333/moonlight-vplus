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

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <cstdlib>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG_TAG "Framegen"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

static std::atomic<uint64_t> g_frameCount{0};

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
Java_com_limelight_framegen_FramegenInterceptor_nativeSetHdrEnabled(
        JNIEnv * /*env*/, jclass /* clazz */, jboolean enabled) {
    FramegenPipeline::setHdrEnabled(enabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSetOutputFrameRate(
        JNIEnv * /*env*/, jclass /* clazz */, jint fps) {
    FramegenPipeline::setOutputFrameRate(static_cast<int32_t>(fps));
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSetTuningConfig(
        JNIEnv * /*env*/, jclass /* clazz */,
        jint internalWidth, jint presentMode, jint slowLsfgThresholdMs, jint presentQueueMax) {
    FramegenPipeline::setTuningConfig(
        static_cast<int32_t>(internalWidth),
        static_cast<int32_t>(presentMode),
        static_cast<int32_t>(slowLsfgThresholdMs),
        static_cast<int32_t>(presentQueueMax));
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
