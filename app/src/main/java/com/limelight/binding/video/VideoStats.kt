package com.limelight.binding.video

import android.os.SystemClock

internal class VideoStats {
    var decoderTimeMs: Long = 0
    var totalTimeMs: Long = 0
    var totalFrames: Int = 0
    var totalFramesReceived: Int = 0
    var totalFramesRendered: Int = 0
    var frameLossEvents: Int = 0
    var framesLost: Int = 0
    var minHostProcessingLatency: Char = 0.toChar()
    var maxHostProcessingLatency: Char = 0.toChar()
    var totalHostProcessingLatency: Int = 0
    var framesWithHostProcessingLatency: Int = 0
    var measurementStartTimestamp: Long = 0
    var renderingTimeMs: Long = 0 // 渲染时间

    fun add(other: VideoStats) {
        decoderTimeMs += other.decoderTimeMs
        totalTimeMs += other.totalTimeMs
        totalFrames += other.totalFrames
        totalFramesReceived += other.totalFramesReceived
        totalFramesRendered += other.totalFramesRendered
        frameLossEvents += other.frameLossEvents
        framesLost += other.framesLost

        // 累加渲染时间
        renderingTimeMs += other.renderingTimeMs

        if (minHostProcessingLatency.code == 0) {
            minHostProcessingLatency = other.minHostProcessingLatency
        } else {
            minHostProcessingLatency = minOf(minHostProcessingLatency, other.minHostProcessingLatency)
        }
        maxHostProcessingLatency = maxOf(maxHostProcessingLatency, other.maxHostProcessingLatency)
        totalHostProcessingLatency += other.totalHostProcessingLatency
        framesWithHostProcessingLatency += other.framesWithHostProcessingLatency

        if (measurementStartTimestamp == 0L) {
            measurementStartTimestamp = other.measurementStartTimestamp
        }

        assert(other.measurementStartTimestamp >= measurementStartTimestamp)
    }

    fun copy(other: VideoStats) {
        decoderTimeMs = other.decoderTimeMs
        totalTimeMs = other.totalTimeMs
        totalFrames = other.totalFrames
        totalFramesReceived = other.totalFramesReceived
        totalFramesRendered = other.totalFramesRendered
        frameLossEvents = other.frameLossEvents
        framesLost = other.framesLost
        minHostProcessingLatency = other.minHostProcessingLatency
        maxHostProcessingLatency = other.maxHostProcessingLatency
        totalHostProcessingLatency = other.totalHostProcessingLatency
        framesWithHostProcessingLatency = other.framesWithHostProcessingLatency
        measurementStartTimestamp = other.measurementStartTimestamp

        // 复制渲染时间
        renderingTimeMs = other.renderingTimeMs
    }

    fun clear() {
        decoderTimeMs = 0
        totalTimeMs = 0
        totalFrames = 0
        totalFramesReceived = 0
        totalFramesRendered = 0
        frameLossEvents = 0
        framesLost = 0
        minHostProcessingLatency = 0.toChar()
        maxHostProcessingLatency = 0.toChar()
        totalHostProcessingLatency = 0
        framesWithHostProcessingLatency = 0
        measurementStartTimestamp = 0
        renderingTimeMs = 0
    }

    fun getFps(): VideoStatsFps {
        val elapsed = (SystemClock.uptimeMillis() - measurementStartTimestamp) / 1000f

        val fps = VideoStatsFps()
        if (elapsed > 0) {
            fps.totalFps = totalFrames / elapsed
            fps.receivedFps = totalFramesReceived / elapsed
            fps.renderedFps = totalFramesRendered / elapsed
        }
        return fps
    }
}

internal class VideoStatsFps {
    var totalFps: Float = 0f
    var receivedFps: Float = 0f
    var renderedFps: Float = 0f
}
