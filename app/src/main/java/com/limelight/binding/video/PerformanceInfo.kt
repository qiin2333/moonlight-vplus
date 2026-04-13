package com.limelight.binding.video

import android.content.Context

class PerformanceInfo {
    var context: Context? = null
    var decoder: String? = null
    var initialWidth: Int = 0
    var initialHeight: Int = 0
    var totalFps: Float = 0f
    var receivedFps: Float = 0f
    var renderedFps: Float = 0f
    var lostFrameRate: Float = 0f
    var rttInfo: Long = 0
    var framesWithHostProcessingLatency: Int = 0
    var minHostProcessingLatency: Float = 0f
    var maxHostProcessingLatency: Float = 0f
    var aveHostProcessingLatency: Float = 0f
    var decodeTimeMs: Float = 0f
    var totalTimeMs: Float = 0f
    var bandWidth: String? = null
    var isHdrActive: Boolean = false // 实际HDR激活状态
    var renderingLatencyMs: Float = 0f // 渲染时间
    var onePercentLowFps: Float = 0f // 1% low FPS (P99帧间隔倒数)
}
