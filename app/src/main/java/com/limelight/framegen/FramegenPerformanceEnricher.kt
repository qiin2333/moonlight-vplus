package com.limelight.framegen

import com.limelight.binding.video.PerformanceInfo

object FramegenPerformanceEnricher {
    private const val TARGET_READY_RATIO = 0.95f

    fun update(
        performanceInfo: PerformanceInfo,
        framegenActive: Boolean,
        baseFps: Int,
        outputFps: Int
    ) {
        performanceInfo.framegenFps =
            if (framegenActive && baseFps > 0 && outputFps > 0) {
                estimateFramegenFps(performanceInfo.renderedFps, baseFps, outputFps)
            } else {
                0f
            }
    }

    private fun estimateFramegenFps(renderedFps: Float, baseFps: Int, outputFps: Int): Float =
        if (outputFps > baseFps) {
            val multiplier = outputFps.toFloat() / baseFps.toFloat()
            (renderedFps * multiplier).coerceAtMost(outputFps.toFloat())
        } else {
            val targetFps = outputFps.toFloat()
            if (renderedFps >= targetFps * TARGET_READY_RATIO) {
                0f
            } else {
                (renderedFps * 2f).coerceAtMost(targetFps)
            }
        }
}
