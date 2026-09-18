package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame

/**
 * Transport-neutral destination for authored DualSense haptics PCM.
 *
 * USB UAC and the wireless bridge use different clocks and packet formats, but their lifecycle
 * and Sunshine-facing input are identical. The coordinator therefore owns this interface rather
 * than a concrete USB pump.
 */
interface DualSenseNativeHapticsSink : WaveformHapticsSink {
    /** Starts transport output and returns true only when PCM can be accepted. */
    override fun start(): Boolean

    override fun submit(frame: Ds5HapticsPcmFrame)

    override fun stop()

    /** Runs [onStopped] after the sink no longer accesses its transport. */
    override fun stopAndThen(onStopped: () -> Unit) {
        stop()
        onStopped()
    }
}
