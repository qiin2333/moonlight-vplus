package com.limelight.framegen

import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/**
 * 阶段 3.1 骨架 —— MediaCodec 输出截流器。
 *
 * 用 ImageReader（PRIVATE/HW buffer 模式）替换 MediaCodec 的 output Surface，
 * 解码出来的每一帧都会以 [HardwareBuffer] 形式回调到 Java 层。本类负责：
 *
 *   1. 从 [ImageReader.acquireLatestImage] 拿到 [android.media.Image]；
 *   2. 取出 [HardwareBuffer]，透传给 native，让 native 验证 AHB 数据通道；
 *   3. **立即 close 该 Image**，把 buffer 槽位还给 ImageReader（不持有引用）。
 *
 * 阶段 3.1 的"成功"标准就是 native 端 logcat 能稳定看到 frame#1、frame#60、frame#120…
 * 屏幕会黑屏（因为帧没有被任何东西 present 回去），这是预期行为，由 UI 文案告知用户。
 *
 * **生命周期**：调用方在 `surfaceChanged` 之前 [create]，在 `surfaceDestroyed` 之后
 * [release]。所有 ImageReader 回调都在专用 HandlerThread 上跑，避免阻塞主线程或解码线程。
 *
 * **maxImages**：取 3。MediaCodec 至少需要 1 个 free slot 才能 dequeueOutputBuffer，
 * 我们额外预留 2 个抗 jitter；过大会浪费显存，过小会反压解码器。
 *
 * **API 29+**：模块 minSdk 已是 29，HardwareBuffer + ImageReader 带 usage 重载从 API 29 起可用。
 */
class FramegenCapture private constructor(
    private val reader: ImageReader,
    private val callbackThread: HandlerThread,
) {

    /** 给 MediaCodec.configure 用的 Surface。生命周期跟 [reader] 绑定。 */
    val surface: Surface = reader.surface

    @Volatile
    private var released = false

    fun release() {
        if (released) return
        released = true
        try {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        } catch (t: Throwable) {
            Log.w(TAG, "ImageReader.close failed: ${t.message}")
        }
        callbackThread.quitSafely()
        Log.i(TAG, "FramegenCapture released")
    }

    companion object {
        private const val TAG = "Framegen"

        /** ImageReader 槽位数。详见类 KDoc。 */
        private const val MAX_IMAGES = 3

        /**
         * @param width  解码器 output 宽
         * @param height 解码器 output 高
         * @return  失败返回 null（设备不支持 / 没 HW buffer 能力），调用方应回退普通路径
         */
        @JvmStatic
        fun create(width: Int, height: Int): FramegenCapture? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.w(TAG, "FramegenCapture 需要 API 29+, 当前 ${Build.VERSION.SDK_INT}")
                return null
            }
            // GPU_SAMPLED_IMAGE 让我们后续可以把这个 AHB 当 sampled texture 喂给 Vulkan；
            // 不要加 CPU usage flag，否则部分驱动会拒绝 MediaCodec 写入。
            val usage = HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
            val reader = try {
                ImageReader.newInstance(
                    width, height,
                    ImageFormat.PRIVATE,
                    MAX_IMAGES,
                    usage
                )
            } catch (t: Throwable) {
                Log.e(TAG, "ImageReader.newInstance ${width}x${height} 失败：${t.message}", t)
                return null
            }

            val thread = HandlerThread("FramegenCapture", Thread.MAX_PRIORITY).apply { start() }
            val handler = Handler(thread.looper)

            FramegenInterceptor.nativeResetFrameCounter()
            Log.i(TAG, "FramegenCapture 创建 ${width}x${height} maxImages=$MAX_IMAGES usage=0x${usage.toString(16)}")

            reader.setOnImageAvailableListener({ r ->
                // acquireLatestImage：丢弃所有更早的，只拿最新——这正好是
                // 阶段 3.1 想要的行为（无 present，越新越好以反映真实延迟）。
                val image = try {
                    r.acquireLatestImage()
                } catch (t: Throwable) {
                    Log.w(TAG, "acquireLatestImage 异常：${t.message}")
                    null
                } ?: return@setOnImageAvailableListener

                try {
                    val hb = image.hardwareBuffer
                    if (hb == null) {
                        Log.w(TAG, "Image.hardwareBuffer == null（驱动不支持 PRIVATE+HW buffer？）")
                    } else {
                        try {
                            val cnt = FramegenInterceptor.nativeOnFrameAvailable(
                                hb,
                                image.width,
                                image.height,
                                image.format,
                                image.timestamp
                            )
                            if (cnt == 1L) {
                                Log.i(TAG, "首帧 AHB 接收成功（${image.width}x${image.height}）")
                            }
                        } finally {
                            // HardwareBuffer 来自 Image，Image.close 会负责释放底层
                            // 引用，但 hardwareBuffer 自身需要显式 close 避免 leak warning。
                            hb.close()
                        }
                    }
                } finally {
                    image.close()
                }
            }, handler)

            return FramegenCapture(reader, thread)
        }
    }
}
