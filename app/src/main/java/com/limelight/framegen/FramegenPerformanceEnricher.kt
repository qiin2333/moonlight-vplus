package com.limelight.framegen

import android.os.SystemClock
import com.limelight.binding.video.PerformanceInfo

object FramegenPerformanceEnricher {
    private const val TARGET_READY_RATIO = 0.95f
    private const val MIN_SAMPLE_MS = 250L

    private var lastStats: FramegenStats? = null
    private var lastStatsMs: Long = 0L

    fun update(
        performanceInfo: PerformanceInfo,
        framegenActive: Boolean,
        baseFps: Int,
        outputFps: Int
    ) {
        if (!framegenActive) {
            lastStats = null
            lastStatsMs = 0L
            performanceInfo.framegenFps = 0f
            performanceInfo.framegenInterpolatedFps = 0f
            performanceInfo.framegenBypassFps = 0f
            performanceInfo.framegenQueueDepth = 0
            performanceInfo.framegenPresenterDrops = 0
            performanceInfo.framegenMode = FramegenStats.MODE_NORMAL
            performanceInfo.framegenInputFps = 0f
            performanceInfo.framegenLsfgWaitMs = 0
            performanceInfo.framegenBlitMs = 0
            return
        }

        val stats = FramegenInterceptor.getStats()
        if (stats != null) {
            updateFromStats(performanceInfo, stats)
            return
        }

        performanceInfo.framegenFps =
            if (baseFps > 0 && outputFps > 0) {
                estimateFramegenFps(performanceInfo.renderedFps, baseFps, outputFps)
            } else {
                0f
            }
    }

    private fun updateFromStats(performanceInfo: PerformanceInfo, stats: FramegenStats) {
        val nowMs = SystemClock.elapsedRealtime()
        val previous = lastStats
        val previousMs = lastStatsMs

        performanceInfo.framegenQueueDepth = stats.queueDepth
        performanceInfo.framegenPresenterDrops = stats.presenterDrops
        performanceInfo.framegenMode = stats.mode
        performanceInfo.framegenInputFps = stats.inputFps
        performanceInfo.framegenLsfgWaitMs = stats.lastLsfgWaitMs
        performanceInfo.framegenBlitMs = stats.lastBlitMs

        if (previous != null && nowMs > previousMs) {
            val elapsedMs = nowMs - previousMs
            if (elapsedMs >= MIN_SAMPLE_MS) {
                val elapsedSec = elapsedMs.toFloat() / 1000f
                val presentedDelta = (stats.presentedFrames - previous.presentedFrames).coerceAtLeast(0L)
                val interpDelta = (stats.interpolatedFrames - previous.interpolatedFrames).coerceAtLeast(0L)
                val bypassDelta = (stats.bypassFrames - previous.bypassFrames).coerceAtLeast(0L)
                val fallbackDelta = (stats.fallbackFrames - previous.fallbackFrames).coerceAtLeast(0L)

                performanceInfo.framegenInterpolatedFps = interpDelta.toFloat() / elapsedSec
                performanceInfo.framegenBypassFps = bypassDelta.toFloat() / elapsedSec
                performanceInfo.framegenFps =
                    if (interpDelta > 0L || fallbackDelta > 0L) {
                        presentedDelta.toFloat() / elapsedSec
                    } else {
                        0f
                    }
            }
        }

        lastStats = stats
        lastStatsMs = nowMs
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
