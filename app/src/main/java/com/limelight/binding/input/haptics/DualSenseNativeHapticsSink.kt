package com.limelight.binding.input.haptics

import com.limelight.nvstream.Ds5HapticsPcmFrame

/**
 * Transport-neutral destination for authored DualSense haptics PCM.
 *
 * USB UAC and the wireless bridge use different clocks and packet formats, but their lifecycle
 * and Sunshine-facing input are identical. The coordinator therefore owns this interface rather
 * than a concrete USB pump.
 */
interface DualSenseNativeHapticsSink {
    /** Starts transport output and returns true only when PCM can be accepted. */
    fun start(): Boolean

    fun submit(frame: Ds5HapticsPcmFrame)

    fun stop()
}
