// JNI 桥 —— moonlight-android :framegen 模块。
//
// 阶段 1：只暴露 nativeSelfTest()，证明 .so 装载、链接 lsfg-vk-framegen 没有未解析符号。
//        真实的 createContextFromAHB / submitFrame / generate 流程在后续阶段补。
//
// 设计约束：
//   - JNI 入口必须 C ABI；所有 Java_* 函数加 JNIEXPORT，让链接器把它从 hidden 默认下导出。
//   - 不在此处直接 #include LSFG_3_1 头，避免编译期把整套 Vulkan 头拉进来拖慢 incremental build。
//     等真正调用时再分离到独立 framegen_pipeline.cpp。

#include <jni.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <pe-parse/parse.h>

#include "extract/trans.hpp"

#include <atomic>
#include <algorithm>
#include <cstdint>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

#define LOG_TAG "Framegen"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__))
#define LOGW(...) ((void)__android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

// 阶段 3 骨架计数器 —— 仅做调用验证，不持有 AHB。
// Java 侧每收到一帧调用 nativeOnFrameAvailable() 一次，这里：
//   - 第一帧：详细打 desc，证明 AHB 真的能从 ImageReader 拿到
//   - 后续帧：每 60 帧打一次心跳，避免 logcat 刷屏
//   - 立即返回，让 Java 侧 close(Image) 把 buffer 还给 ImageReader
// 真正持有/导入 AHB 进 Vulkan 是阶段 3.2（FramegenContext）做的事，
// 阶段 3.1 只验证 MediaCodec → ImageReader → JNI 数据通道通了。
static std::atomic<uint64_t> g_frameCount{0};

namespace {
constexpr uint32_t kGenerateShaderResourceId = 256;

struct LosslessProbeState {
    uint32_t rcdataCount{0};
    std::vector<uint8_t> generateShaderDxbc;
};

int onLosslessResource(void* context, const peparse::resource& res) {
    auto* state = static_cast<LosslessProbeState*>(context);
    if (state == nullptr || res.type != peparse::RT_RCDATA || res.buf == nullptr || res.buf->bufLen <= 0)
        return 0;

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
    const std::string msg = "framegen-skeleton-ok";
    LOGI("nativeSelfTest -> %s", msg.c_str());
    return env->NewStringUTF(msg.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeOnFrameAvailable(
        JNIEnv *env, jclass /* clazz */,
        jobject jHwBuffer, jint width, jint height, jint format, jlong timestampNs) {
    if (jHwBuffer == nullptr) {
        LOGW("nativeOnFrameAvailable: null HardwareBuffer");
        return 0;
    }

    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, jHwBuffer);
    if (ahb == nullptr) {
        LOGE("AHardwareBuffer_fromHardwareBuffer returned NULL");
        return 0;
    }

    const uint64_t n = g_frameCount.fetch_add(1, std::memory_order_relaxed) + 1;

    if (n == 1 || (n % 60) == 0) {
        AHardwareBuffer_Desc desc{};
        AHardwareBuffer_describe(ahb, &desc);
        LOGI("frame#%llu ahb=%p reader=%dx%d/fmt=%d  ahb=%ux%u/fmt=0x%x/usage=0x%llx/layers=%u/stride=%u  ts=%lld",
             (unsigned long long)n, ahb,
             width, height, format,
             desc.width, desc.height, desc.format,
             (unsigned long long)desc.usage,
             desc.layers, desc.stride,
             (long long)timestampNs);
    }

    // 阶段 3.1 骨架：不 acquire、不导入 Vulkan，直接返回让 Java 侧 close(Image)。
    // 返回累计帧数，方便 Java 侧做"是否真的在收帧"的快速判断。
    return static_cast<jlong>(n);
}

extern "C" JNIEXPORT void JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeResetFrameCounter(
        JNIEnv * /* env */, jclass /* clazz */) {
    const uint64_t prev = g_frameCount.exchange(0, std::memory_order_relaxed);
    LOGI("frame counter reset (was %llu)", (unsigned long long)prev);
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
        LOGI("nativeProbeLosslessDll(%s) -> %s", rawPath, result.c_str());
    } catch (const std::exception& e) {
        result = std::string("lossless-dll-probe-failed: ") + e.what();
        LOGE("nativeProbeLosslessDll(%s) failed: %s", rawPath, e.what());
    }

    env->ReleaseStringUTFChars(jDllPath, rawPath);
    return env->NewStringUTF(result.c_str());
}
