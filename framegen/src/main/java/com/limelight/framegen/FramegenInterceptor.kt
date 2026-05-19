package com.limelight.framegen

import android.os.Build
import android.util.Log

/**
 * Lossless Scaling Frame Generation 拦截器（占位实现 / 阶段 1）。
 *
 * 设计目标（后续阶段会逐步落地）：
 *   1. 持有一个 [android.media.ImageReader]，作为 MediaCodec 的 output Surface，
 *      让解码后的视频帧以 AHardwareBuffer 形式可供 native 侧读取。
 *   2. JNI 层调用上游 `LSFG_3_1::createContextFromAHB`，把输入 AHB 与输出 AHB 注册进
 *      lsfg-vk-framegen 内部的 Vulkan device。
 *   3. 每收到一帧解码帧：submitFrame → framegen 异步生成 N-1 张插值帧 → 按目标 FPS 节奏
 *      把原帧 + 插值帧依次提交给最终显示 Surface（SurfaceView）。
 *   4. 失败时透明降级——把原始解码 Surface 直接交还给 [com.limelight.Game]，等价于关闭功能。
 *
 * 阶段 1（当前）：只验证 native 库能加载、JNI 方法能调用。无任何真实功能。
 */
class FramegenInterceptor {

    /**
     * native 端 framegen 上下文句柄。0 表示未初始化。
     * 用 Long 是因为 native 侧持有的是裸指针，64 位设备需要 8 字节。
     */
    @Volatile
    private var nativeHandle: Long = 0L

    /**
     * 仅供阶段 1 自检使用：调用 native side 返回一个固定字符串，证明 .so 装载成功。
     */
    fun selfTest(): String {
        if (!isAvailable()) {
            return "unavailable (sdk=${Build.VERSION.SDK_INT}, abi=${Build.SUPPORTED_ABIS.joinToString()})"
        }
        return try {
            nativeSelfTest()
        } catch (t: UnsatisfiedLinkError) {
            Log.e(TAG, "native lib not loaded", t)
            "missing-native"
        }
    }

    /**
     * 阶段 4 自检：验证用户提供的 Lossless.dll 能否在 native 侧被解析，
     * 并成功把至少一份 DXBC shader 转成 SPIR-V。
     */
    fun probeLosslessDll(dllPath: String): String {
        if (!isAvailable()) {
            return "unavailable (sdk=${Build.VERSION.SDK_INT}, abi=${Build.SUPPORTED_ABIS.joinToString()})"
        }
        return try {
            nativeProbeLosslessDll(dllPath)
        } catch (t: UnsatisfiedLinkError) {
            Log.e(TAG, "native probe missing", t)
            "missing-native"
        } catch (t: Throwable) {
            Log.e(TAG, "native probe failed", t)
            "probe-exception: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    /**
     * 后续阶段才实现。当前抛 NotImplementedError 是为了让上层任何意外调用都立即暴露。
     */
    fun initialize(width: Int, height: Int, multiplier: Int): Boolean {
        throw NotImplementedError("FramegenInterceptor 处于阶段 1 骨架，尚未实现初始化逻辑")
    }

    fun release() {
        // 阶段 1：noop。后续要 nativeRelease(nativeHandle) 并置 0。
    }

    // ---- native 入口（与 framegen/src/main/cpp/jni_bridge.cpp 一一对应）----
    private external fun nativeSelfTest(): String
    private external fun nativeProbeLosslessDll(dllPath: String): String

    companion object {
        private const val TAG = "Framegen"

        @Volatile
        private var libLoaded: Boolean = false

        init {
            try {
                System.loadLibrary("moonlight-framegen")
                libLoaded = true
            } catch (t: UnsatisfiedLinkError) {
                // 在不支持的 ABI / 设备上构建出来的 APK 里可能没有这个 .so——
                // 此时调用方应通过 isAvailable() 提前判断，而不是 crash。
                Log.w(TAG, "libmoonlight-framegen.so 未加载，framegen 功能不可用：${t.message}")
                libLoaded = false
            }
        }

        /**
         * 判断当前设备是否具备启用 framegen 的最低条件。
         * 阶段 1 仅做最粗粒度过滤；具体 GPU 白名单（Adreno 7xx+）放到后续阶段。
         */
        @JvmStatic
        fun isAvailable(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
            if (Build.SUPPORTED_64_BIT_ABIS.none { it == "arm64-v8a" }) return false
            return libLoaded
        }

        @JvmStatic
        fun configureLosslessDllPath(dllPath: String?) {
            if (!isAvailable() || dllPath.isNullOrBlank()) return
            try {
                nativeSetLosslessDllPath(dllPath)
            } catch (t: Throwable) {
                Log.w(TAG, "failed to configure Lossless.dll path", t)
            }
        }

        /**
         * 阶段 3.1 骨架：把 ImageReader 拿到的 HardwareBuffer 透传给 native 端，
         * native 只做计数/log 打印，不导入 Vulkan、不持有引用。
         *
         * @param hwBuffer  从 Image.getHardwareBuffer() 拿到的 HardwareBuffer（不为 null）
         * @param width     ImageReader 配置宽（≠ AHB 实际宽，因为驱动可能 align）
         * @param height    ImageReader 配置高
         * @param format    ImageReader 配置 format（ImageFormat.PRIVATE 时取 -1 即可）
         * @param timestampNs  Image.getTimestamp() 返回的 mono ns
         * @return  到目前为止累计收到的帧数（≥1），0 表示 native 出错
         */
        @JvmStatic
        external fun nativeOnFrameAvailable(
            hwBuffer: android.hardware.HardwareBuffer,
            width: Int,
            height: Int,
            format: Int,
            timestampNs: Long
        ): Long

        @JvmStatic
        external fun nativeResetFrameCounter()

        @JvmStatic
        private external fun nativeSetLosslessDllPath(dllPath: String)
    }
}
