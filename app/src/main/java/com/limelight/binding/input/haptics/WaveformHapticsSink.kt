package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame

/** Local waveform output; the DS5 name on the input describes the wire format, not the motor. */
interface WaveformHapticsSink {
    /** False after a transport failure or shutdown, even if startup previously succeeded. */
    val isOperational: Boolean get() = true
    val playbackControl: WaveformPlaybackControl? get() = null
    val channelTest: WaveformChannelTest? get() = null
    val releaseFailure: Throwable? get() = null
    fun start(): Boolean
    fun submit(frame: Ds5HapticsPcmFrame)
    fun stop()
    fun stopAndThen(onStopped: () -> Unit) {
        stop()
        onStopped()
    }
}

/** Optional motor ownership and test capabilities; routing never casts to a device implementation. */
interface WaveformPlaybackControl {
    val playbackActive: Boolean
    var onPlaybackChanged: ((Boolean) -> Unit)?
}

interface WaveformChannelTest {
    val isTesting: Boolean
    val canTest: Boolean
    fun testChannels()
    fun cancelTest()
}
