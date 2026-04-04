package com.limelight.binding.video

import android.content.Context

class PerformanceInfo {
    @JvmField var context: Context? = null
    @JvmField var decoder: String? = null
    @JvmField var initialWidth: Int = 0
    @JvmField var initialHeight: Int = 0
    @JvmField var totalFps: Float = 0f
    @JvmField var receivedFps: Float = 0f
    @JvmField var renderedFps: Float = 0f
    @JvmField var lostFrameRate: Float = 0f
    @JvmField var rttInfo: Long = 0
    @JvmField var framesWithHostProcessingLatency: Int = 0
    @JvmField var minHostProcessingLatency: Float = 0f
    @JvmField var maxHostProcessingLatency: Float = 0f
    @JvmField var aveHostProcessingLatency: Float = 0f
    @JvmField var decodeTimeMs: Float = 0f
    @JvmField var totalTimeMs: Float = 0f
    @JvmField var bandWidth: String? = null
    @JvmField var isHdrActive: Boolean = false // 实际HDR激活状态
    @JvmField var renderingLatencyMs: Float = 0f // 渲染时间
    @JvmField var onePercentLowFps: Float = 0f // 1% low FPS (P99帧间隔倒数)
}
