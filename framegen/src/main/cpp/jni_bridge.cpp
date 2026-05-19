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
#include <string>

#define LOG_TAG "Framegen"
#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__))

extern "C" JNIEXPORT jstring JNICALL
Java_com_limelight_framegen_FramegenInterceptor_nativeSelfTest(JNIEnv *env, jobject /* thiz */) {
    // 注意：阶段 1 故意不在这里调任何 lsfg-vk-framegen API。
    // 仅返回固定字符串，验证 .so 能装载 + JNI 调用走得通即可。
    // 链接器若找不到 lsfg-vk-framegen 的符号也会在 .so 加载阶段就失败，
    // 因此就算这里不直接调用，整个静态库依然会被作为链接验证的一部分。
    const std::string msg = "framegen-skeleton-ok";
    LOGI("nativeSelfTest -> %s", msg.c_str());
    return env->NewStringUTF(msg.c_str());
}
