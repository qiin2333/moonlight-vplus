package com.limelight.binding.video

import android.os.SystemClock

internal class VideoStats {
    @JvmField var decoderTimeMs: Long = 0
    @JvmField var totalTimeMs: Long = 0
    @JvmField var totalFrames: Int = 0
    @JvmField var totalFramesReceived: Int = 0
    @JvmField var totalFramesRendered: Int = 0
    @JvmField var frameLossEvents: Int = 0
    @JvmField var framesLost: Int = 0
    @JvmField var minHostProcessingLatency: Char = 0.toChar()
    @JvmField var maxHostProcessingLatency: Char = 0.toChar()
    @JvmField var totalHostProcessingLatency: Int = 0
    @JvmField var framesWithHostProcessingLatency: Int = 0
    @JvmField var measurementStartTimestamp: Long = 0
    @JvmField var renderingTimeMs: Long = 0 // 渲染时间

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
    @JvmField var totalFps: Float = 0f
    @JvmField var receivedFps: Float = 0f
    @JvmField var renderedFps: Float = 0f
}
